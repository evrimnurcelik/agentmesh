package io.agentmesh.services

import io.agentmesh.db.Agents
import io.agentmesh.db.Capabilities
import io.agentmesh.db.DatabaseFactory.query
import io.agentmesh.db.Matches
import io.agentmesh.models.*
import io.agentmesh.util.newId
import io.agentmesh.util.nowIso
import io.agentmesh.util.toJsonString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.slf4j.LoggerFactory
import java.time.Instant

object MatchingService {

    private val log = LoggerFactory.getLogger(MatchingService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)

    /** Fire-and-forget match computation after agent registration/update */
    fun scheduleMatching(agentId: String, minScore: Int = 40) {
        scope.launch {
            try {
                runMatchingForAgent(agentId, minScore)
            } catch (e: Exception) {
                log.error("Matching failed for agent $agentId", e)
            }
        }
    }

    suspend fun runMatchingForAgent(agentId: String, minScore: Int = 40) = query {
        val agent = Agents.select { Agents.id eq agentId }.singleOrNull() ?: return@query
        val needs = agent[Agents.needs].toList()
        if (needs.isEmpty()) return@query

        // Load capability map for domain scoring
        val capMap = Capabilities.selectAll()
            .associate { it[Capabilities.id] to it[Capabilities.category] }

        // Load all other public agents
        val candidates = Agents
            .select { (Agents.public eq true) and (Agents.id neq agentId) }
            .toList()

        for (candidate in candidates) {
            val agentHas       = agent[Agents.has].toList()
            val agentNeeds     = needs
            val candidateHas   = candidate[Agents.has].toList()
            val candidateNeeds = candidate[Agents.needs].toList()

            val result = score(
                aHas = agentHas, aNeeds = agentNeeds,
                bHas = candidateHas, bNeeds = candidateNeeds,
                aName = agent[Agents.name], bName = candidate[Agents.name],
                bStats = candidate[Agents.stats],
                capMap = capMap
            )

            if (result.score < minScore) continue

            // Skip if already an active match in either direction
            val existingActive = Matches.select {
                (Matches.status eq "active") and (
                    ((Matches.agentAId eq agentId) and (Matches.agentBId eq candidate[Agents.id])) or
                    ((Matches.agentAId eq candidate[Agents.id]) and (Matches.agentBId eq agentId))
                )
            }.count()

            if (existingActive > 0) continue

            // Upsert the match suggestion
            Matches.upsert(Matches.agentAId, Matches.agentBId) {
                it[id]             = newId("mx")
                it[agentAId]       = agentId
                it[agentBId]       = candidate[Agents.id]
                it[score]          = result.score
                it[scoreBreakdown] = result.breakdown.toJsonString()
                it[reason]         = result.reason
                it[coveringNeeds]  = result.coveringNeeds.toTypedArray()
                it[status]         = "pending"
                it[approvedByA]    = false
                it[approvedByB]    = false
                it[createdAt]      = Instant.now()
                it[updatedAt]      = Instant.now()
            }

            log.debug("Match upserted: $agentId <-> ${candidate[Agents.id]} score=${result.score}")
        }
    }

    data class ScoreResult(
        val score: Int,
        val breakdown: ScoreBreakdown,
        val coveringNeeds: List<String>,
        val reason: String
    )

    fun score(
        aHas: List<String>, aNeeds: List<String>,
        bHas: List<String>, bNeeds: List<String>,
        aName: String, bName: String,
        bStats: String,
        capMap: Map<String, String>
    ): ScoreResult {
        // 60% – tool overlap: how many of A's needs B covers
        val covering = aNeeds.filter { it in bHas }
        val overlapRatio = if (aNeeds.isEmpty()) 0.0 else covering.size.toDouble() / aNeeds.size
        val toolOverlap = (overlapRatio * 60).toInt()

        // 25% – domain proximity via shared capability categories
        val aCats = aHas.mapNotNull { capMap[it] }.toSet()
        val bCats = bHas.mapNotNull { capMap[it] }.toSet()
        val sharedCats = aCats.intersect(bCats)
        val maxCats = maxOf(aCats.size, bCats.size, 1)
        val domainProximity = ((sharedCats.size.toDouble() / maxCats) * 25).toInt()

        // 15% – reliability from B's historical stats (default 80% for new agents)
        val statsJson = bStats
        val successCount = Regex(""""success_count":(\d+)""").find(statsJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val failCount    = Regex(""""fail_count":(\d+)""").find(statsJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = successCount + failCount
        val successRate = if (total == 0) 0.8 else successCount.toDouble() / total
        val reliability = (successRate * 15).toInt()

        val finalScore = toolOverlap + domainProximity + reliability

        val reason = when {
            covering.size == aNeeds.size && aNeeds.isNotEmpty() ->
                "$bName covers all ${aNeeds.size} of $aName's declared needs"
            covering.isNotEmpty() ->
                "$bName handles ${covering.size} of $aName's needs: ${covering.joinToString(", ")}"
            else ->
                "$bName shares ${sharedCats.joinToString(", ")} domain context with $aName"
        }

        return ScoreResult(
            score        = finalScore,
            breakdown    = ScoreBreakdown(toolOverlap, domainProximity, reliability),
            coveringNeeds = covering,
            reason       = reason
        )
    }

    fun buildContract(agentA: ResultRow, agentB: ResultRow, capMap: Map<String, List<String>>): MatchContract {
        val coveringTools = agentA[Agents.needs].filter { it in agentB[Agents.has] }
        val taskTypes = coveringTools.flatMap { capMap[it] ?: emptyList() }.distinct()
        return MatchContract(
            allowedTaskTypes = taskTypes,
            rateLimit        = "100/hour",
            expiresAt        = null
        )
    }
}
