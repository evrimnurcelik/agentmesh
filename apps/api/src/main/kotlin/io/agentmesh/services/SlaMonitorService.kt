package io.agentmesh.services

import io.agentmesh.db.*
import io.agentmesh.db.DatabaseFactory.query
import io.agentmesh.models.SlaRequirement
import io.agentmesh.util.fromJson
import io.agentmesh.util.newId
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.*
import org.slf4j.LoggerFactory
import java.time.Instant

object SlaMonitorService {

    private val log = LoggerFactory.getLogger(SlaMonitorService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        scope.launch {
            log.info("SlaMonitorService started (15m interval)")
            while (isActive) {
                try {
                    checkSlaCompliance()
                } catch (e: Exception) {
                    log.error("SLA check cycle failed", e)
                }
                delay(15 * 60 * 1000L) // 15 minutes
            }
        }
    }

    private suspend fun checkSlaCompliance() {
        val now = Instant.now()

        val matchesWithSla = query {
            Matches.select {
                (Matches.status eq "active") and (Matches.sla.isNotNull())
            }.toList()
        }

        for (match in matchesWithSla) {
            val slaStr = match[Matches.sla] ?: continue
            if (slaStr.isBlank() || slaStr == "null") continue

            val sla = try {
                slaStr.fromJson<SlaRequirement>()
            } catch (_: Exception) {
                continue
            }

            val matchId = match[Matches.id]
            val agentBId = match[Matches.agentBId]
            val windowStart = now.minusSeconds(sla.windowHours * 3600L)

            query {
                // Check failure rate
                val delegations = Delegations.select {
                    (Delegations.toAgentId eq agentBId) and
                    (Delegations.matchId eq matchId) and
                    (Delegations.createdAt.greaterEq(windowStart))
                }.toList()

                val total = delegations.size
                if (total > 0) {
                    val failed = delegations.count { it[Delegations.status] in listOf("failed", "timed_out") }
                    val failureRate = (failed.toDouble() / total) * 100

                    if (failureRate > sla.maxFailureRatePct) {
                        insertViolation(matchId, agentBId, "failure_rate", failureRate, sla.maxFailureRatePct, windowStart, now)
                    }

                    // Check average latency
                    val latencies = delegations.mapNotNull { it[Delegations.durationMs] }
                    if (latencies.isNotEmpty()) {
                        val avgLatency = latencies.average()
                        if (avgLatency > sla.maxLatencyMs) {
                            insertViolation(matchId, agentBId, "latency", avgLatency, sla.maxLatencyMs.toDouble(), windowStart, now)
                        }
                    }
                }

                // Check uptime via health checks
                val healthChecks = AgentHealthChecks.select {
                    (AgentHealthChecks.agentId eq agentBId) and
                    (AgentHealthChecks.checkedAt.greaterEq(windowStart))
                }.toList()

                if (healthChecks.isNotEmpty()) {
                    val online = healthChecks.count { it[AgentHealthChecks.status] != "offline" }
                    val uptimePct = (online.toDouble() / healthChecks.size) * 100

                    if (uptimePct < sla.minUptimePct) {
                        insertViolation(matchId, agentBId, "uptime", uptimePct, sla.minUptimePct, windowStart, now)
                    }
                }
            }
        }
    }

    private fun insertViolation(
        matchId: String, agentId: String, type: String,
        measured: Double, threshold: Double,
        windowStart: Instant, windowEnd: Instant
    ) {
        // Check if we already have a recent violation for this match/type (avoid duplicate alerts)
        val recentViolation = SlaViolations.select {
            (SlaViolations.matchId eq matchId) and
            (SlaViolations.violationType eq type) and
            (SlaViolations.createdAt.greaterEq(windowEnd.minusSeconds(3600))) // 1 hour dedup
        }.count()

        if (recentViolation > 0) return

        SlaViolations.insert {
            it[id] = newId("sv")
            it[SlaViolations.matchId] = matchId
            it[violatingAgentId] = agentId
            it[violationType] = type
            it[measuredValue] = measured.toBigDecimal()
            it[SlaViolations.threshold] = threshold.toBigDecimal()
            it[SlaViolations.windowStart] = windowStart
            it[SlaViolations.windowEnd] = windowEnd
            it[resolved] = false
            it[createdAt] = windowEnd
        }

        // Send email notifications
        val agentName = Agents.select { Agents.id eq agentId }.singleOrNull()?.get(Agents.name) ?: agentId
        val match = Matches.select { Matches.id eq matchId }.singleOrNull() ?: return
        val otherAgentId = if (match[Matches.agentAId] == agentId) match[Matches.agentBId] else match[Matches.agentAId]
        val otherOwnerEmail = Agents.select { Agents.id eq otherAgentId }.singleOrNull()?.get(Agents.ownerEmail) ?: return

        EmailService.sendSlaViolation(
            otherOwnerEmail, agentName, type,
            "%.2f".format(measured), "%.2f".format(threshold)
        )

        log.warn("SLA violation: match=$matchId agent=$agentId type=$type measured=$measured threshold=$threshold")
    }
}
