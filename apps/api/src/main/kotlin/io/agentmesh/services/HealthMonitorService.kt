package io.agentmesh.services

import io.agentmesh.db.AgentHealthChecks
import io.agentmesh.db.Agents
import io.agentmesh.db.DatabaseFactory.query
import io.agentmesh.db.Matches
import io.agentmesh.util.newId
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.*
import org.slf4j.LoggerFactory
import java.time.Instant

object HealthMonitorService {

    private val log = LoggerFactory.getLogger(HealthMonitorService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        scope.launch {
            log.info("HealthMonitorService started (60s interval)")
            while (isActive) {
                try {
                    runHealthChecks()
                } catch (e: Exception) {
                    log.error("Health check cycle failed", e)
                }
                delay(60_000)
            }
        }
    }

    private suspend fun runHealthChecks() {
        val now = Instant.now()
        val fiveMinutesAgo = now.minusSeconds(300)

        query {
            // For agents that are online/idle with recent pings, record their status
            val activeAgents = Agents.select {
                (Agents.status neq "offline") and
                (Agents.lastPing.isNotNull())
            }.toList()

            for (agent in activeAgents) {
                val lastPing = agent[Agents.lastPing]
                val agentId = agent[Agents.id]

                if (lastPing != null && lastPing.isBefore(fiveMinutesAgo)) {
                    // Agent hasn't pinged in 5+ minutes — mark offline
                    Agents.update({ Agents.id eq agentId }) {
                        it[status] = "offline"
                        it[updatedAt] = now
                    }

                    AgentHealthChecks.insert {
                        it[id] = newId("hc")
                        it[AgentHealthChecks.agentId] = agentId
                        it[AgentHealthChecks.status] = "offline"
                        it[latencyMs] = null
                        it[checkedAt] = now
                    }

                    // Notify matched agents' owners
                    val matchedOwners = Matches.innerJoin(Agents, { agentBId }, { Agents.id })
                        .slice(Agents.ownerEmail, Agents.name)
                        .select {
                            (Matches.agentAId eq agentId) and (Matches.status eq "active")
                        }.map { it[Agents.ownerEmail] to it[Agents.name] }

                    for ((email, matchedName) in matchedOwners) {
                        EmailService.sendAgentOffline(email, agent[Agents.name], matchedName)
                    }
                } else {
                    // Agent is active, record check
                    AgentHealthChecks.insert {
                        it[id] = newId("hc")
                        it[AgentHealthChecks.agentId] = agentId
                        it[AgentHealthChecks.status] = agent[Agents.status]
                        it[latencyMs] = null
                        it[checkedAt] = now
                    }
                }
            }
        }
    }
}
