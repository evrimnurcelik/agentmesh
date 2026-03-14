package io.agentmesh.routes

import io.agentmesh.db.AgentHealthChecks
import io.agentmesh.db.Agents
import io.agentmesh.db.DatabaseFactory.query
import io.agentmesh.models.ApiError
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import java.time.Instant

fun Route.healthRoutes() {

    // ─────────────────────────────────────────
    // GET /agents/{agentId}/health — uptime & latency summary
    // ─────────────────────────────────────────
    get("/agents/{agentId}/health") {
        val agentId = call.parameters["agentId"]!!

        val agent = query {
            Agents.select { Agents.id eq agentId }.singleOrNull()
        }
        if (agent == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", "not_found", "Agent not found"))
            return@get
        }

        val now = Instant.now()
        val sevenDaysAgo = now.minusSeconds(7 * 24 * 3600)
        val thirtyDaysAgo = now.minusSeconds(30 * 24 * 3600)

        val checks = query {
            AgentHealthChecks.select { AgentHealthChecks.agentId eq agentId }
                .orderBy(AgentHealthChecks.checkedAt, SortOrder.DESC)
                .limit(500)
                .toList()
        }

        val checks7d = checks.filter { it[AgentHealthChecks.checkedAt].isAfter(sevenDaysAgo) }
        val checks30d = checks.filter { it[AgentHealthChecks.checkedAt].isAfter(thirtyDaysAgo) }

        val uptime7d = if (checks7d.isNotEmpty()) {
            val online = checks7d.count { it[AgentHealthChecks.status] != "offline" }
            Math.round(online.toDouble() / checks7d.size * 10000) / 100.0
        } else null

        val uptime30d = if (checks30d.isNotEmpty()) {
            val online = checks30d.count { it[AgentHealthChecks.status] != "offline" }
            Math.round(online.toDouble() / checks30d.size * 10000) / 100.0
        } else null

        val latencyChecks = checks.take(100).mapNotNull { it[AgentHealthChecks.latencyMs] }
        val avgLatencyMs = if (latencyChecks.isNotEmpty()) latencyChecks.average().toInt() else null

        val currentStatus = if (checks.isNotEmpty()) checks.first()[AgentHealthChecks.status] else agent[Agents.status]

        val last50 = checks.take(50).map { row ->
            mapOf(
                "id" to row[AgentHealthChecks.id],
                "status" to row[AgentHealthChecks.status],
                "latency_ms" to row[AgentHealthChecks.latencyMs],
                "checked_at" to row[AgentHealthChecks.checkedAt].toString()
            )
        }

        call.respond(mapOf(
            "agent_id" to agentId,
            "current_status" to currentStatus,
            "uptime_7d" to uptime7d,
            "uptime_30d" to uptime30d,
            "avg_latency_ms" to avgLatencyMs,
            "checks" to last50
        ))
    }

    // ─────────────────────────────────────────
    // GET /agents/{agentId}/health/history — paginated history
    // ─────────────────────────────────────────
    get("/agents/{agentId}/health/history") {
        val agentId = call.parameters["agentId"]!!
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
        val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0).coerceIn(0, 100000)

        val checks = query {
            AgentHealthChecks.select { AgentHealthChecks.agentId eq agentId }
                .orderBy(AgentHealthChecks.checkedAt, SortOrder.DESC)
                .limit(limit, offset)
                .map { row ->
                    mapOf(
                        "id" to row[AgentHealthChecks.id],
                        "status" to row[AgentHealthChecks.status],
                        "latency_ms" to row[AgentHealthChecks.latencyMs],
                        "checked_at" to row[AgentHealthChecks.checkedAt].toString()
                    )
                }
        }

        call.respond(mapOf("checks" to checks, "count" to checks.size))
    }
}
