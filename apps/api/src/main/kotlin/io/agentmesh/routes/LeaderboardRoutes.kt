package io.agentmesh.routes

import io.agentmesh.db.Agents
import io.agentmesh.db.DatabaseFactory.query
import io.agentmesh.models.AgentStats
import io.agentmesh.util.fromJson
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import java.time.Instant

private data class CacheEntry(val data: Any, val timestamp: Long)

fun Route.leaderboardRoutes() {

    val cache = mutableMapOf<String, CacheEntry>()
    val cacheTtlMs = 5 * 60 * 1000L // 5 minutes

    fun getCached(key: String): Any? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > cacheTtlMs) {
            cache.remove(key)
            return null
        }
        return entry.data
    }

    fun putCache(key: String, data: Any) {
        cache[key] = CacheEntry(data, System.currentTimeMillis())
    }

    // ─────────────────────────────────────────
    // GET /leaderboard/delegations — top by delegations_sent
    // ─────────────────────────────────────────
    get("/leaderboard/delegations") {
        val cached = getCached("delegations")
        if (cached != null) {
            call.respond(cached)
            return@get
        }

        val agents = query {
            Agents.select { Agents.public eq true }
                .orderBy(Agents.createdAt, SortOrder.DESC)
                .map { row ->
                    val stats = row[Agents.stats].fromJson<AgentStats>()
                    mapOf(
                        "id" to row[Agents.id],
                        "name" to row[Agents.name],
                        "framework" to row[Agents.framework],
                        "status" to row[Agents.status],
                        "trust_tier" to row[Agents.trustTier],
                        "delegations_sent" to stats.delegationsSent,
                        "delegations_received" to stats.delegationsReceived,
                        "success_count" to stats.successCount,
                        "fail_count" to stats.failCount
                    )
                }
                .sortedByDescending { it["delegations_sent"] as Int }
                .take(50)
        }

        val result = mapOf("agents" to agents, "updated_at" to Instant.now().toString())
        putCache("delegations", result)
        call.respond(result)
    }

    // ─────────────────────────────────────────
    // GET /leaderboard/reliability — top by success rate (min 10 total)
    // ─────────────────────────────────────────
    get("/leaderboard/reliability") {
        val cached = getCached("reliability")
        if (cached != null) {
            call.respond(cached)
            return@get
        }

        val agents = query {
            Agents.select { Agents.public eq true }
                .map { row ->
                    val stats = row[Agents.stats].fromJson<AgentStats>()
                    val total = stats.successCount + stats.failCount
                    val rate = if (total > 0) stats.successCount.toDouble() / total else 0.0
                    mapOf(
                        "id" to row[Agents.id],
                        "name" to row[Agents.name],
                        "framework" to row[Agents.framework],
                        "status" to row[Agents.status],
                        "trust_tier" to row[Agents.trustTier],
                        "success_rate" to Math.round(rate * 10000) / 100.0,
                        "total_delegations" to total,
                        "success_count" to stats.successCount,
                        "fail_count" to stats.failCount
                    )
                }
                .filter { (it["total_delegations"] as Int) >= 10 }
                .sortedByDescending { it["success_rate"] as Double }
                .take(50)
        }

        val result = mapOf("agents" to agents, "updated_at" to Instant.now().toString())
        putCache("reliability", result)
        call.respond(result)
    }

    // ─────────────────────────────────────────
    // GET /leaderboard/wanted — most-wanted capabilities
    // ─────────────────────────────────────────
    get("/leaderboard/wanted") {
        val cached = getCached("wanted")
        if (cached != null) {
            call.respond(cached)
            return@get
        }

        val wanted = query {
            val needsCounts = mutableMapOf<String, Int>()
            Agents.select { Agents.public eq true }
                .forEach { row ->
                    row[Agents.needs].forEach { tool ->
                        needsCounts[tool] = (needsCounts[tool] ?: 0) + 1
                    }
                }
            needsCounts.entries
                .sortedByDescending { it.value }
                .take(30)
                .map { mapOf("tool" to it.key, "demanded_by" to it.value) }
        }

        val result = mapOf("capabilities" to wanted, "updated_at" to Instant.now().toString())
        putCache("wanted", result)
        call.respond(result)
    }
}
