package io.agentmesh.routes

import io.agentmesh.db.Agents
import io.agentmesh.db.DatabaseFactory.query
import io.agentmesh.db.Delegations
import io.agentmesh.models.*
import io.agentmesh.util.authenticatedAgentId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*

fun Route.chainRoutes() {

    // ─────────────────────────────────────────
    // GET /chains/{chainId}
    // Returns a full view of a delegation chain — all hops, statuses, timings.
    // ─────────────────────────────────────────
    get("/chains/{chainId}") {
        val chainId = call.parameters["chainId"]!!
        val agentId = call.authenticatedAgentId()
            ?: return@get call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))

        val hops = query {
            Delegations
                .join(Agents, JoinType.LEFT, Delegations.fromAgentId, Agents.id)
                .select { Delegations.chainId eq chainId }
                .orderBy(Delegations.chainDepth, SortOrder.ASC)
                .map { row ->
                    val toAgentName = Agents
                        .slice(Agents.name)
                        .select { Agents.id eq row[Delegations.toAgentId] }
                        .singleOrNull()?.get(Agents.name) ?: row[Delegations.toAgentId]

                    ChainHop(
                        depth        = row[Delegations.chainDepth],
                        delegationId = row[Delegations.id],
                        fromAgent    = row[Agents.name],
                        toAgent      = toAgentName,
                        task         = row[Delegations.task],
                        status       = row[Delegations.status],
                        durationMs   = row[Delegations.durationMs]
                    )
                }
        }

        if (hops.isEmpty()) {
            call.respond(HttpStatusCode.NotFound,
                ApiError("not_found", "not_found", "Chain not found"))
            return@get
        }

        // Verify caller is party to at least one hop in the chain
        val isParty = query {
            Delegations.select {
                (Delegations.chainId eq chainId) and
                ((Delegations.fromAgentId eq agentId) or (Delegations.toAgentId eq agentId))
            }.count() > 0
        }
        if (!isParty) {
            call.respond(HttpStatusCode.Forbidden,
                ApiError("forbidden", "forbidden", "Not a party to this chain"))
            return@get
        }

        val overallStatus = when {
            hops.any { it.status == "failed" || it.status == "timed_out" } -> "failed"
            hops.all { it.status == "completed" } -> "completed"
            else -> "running"
        }
        val totalDuration = hops.sumOf { it.durationMs ?: 0 }

        call.respond(DelegationChain(
            chainId         = chainId,
            hops            = hops,
            status          = overallStatus,
            totalDurationMs = totalDuration
        ))
    }

    // ─────────────────────────────────────────
    // GET /chains — list chains I'm involved in
    // ─────────────────────────────────────────
    get("/chains") {
        val agentId = call.authenticatedAgentId()
            ?: return@get call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))

        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)

        // Find distinct chainIds where this agent participates, depth=0 only (chain initiators)
        val chainIds = query {
            Delegations
                .slice(Delegations.chainId)
                .select {
                    (Delegations.chainDepth eq 0) and
                    (Delegations.chainId.isNotNull()) and
                    ((Delegations.fromAgentId eq agentId) or (Delegations.toAgentId eq agentId))
                }
                .orderBy(Delegations.createdAt, SortOrder.DESC)
                .limit(limit)
                .mapNotNull { it[Delegations.chainId] }
                .distinct()
        }

        // For each chain, get a summary
        val summaries = chainIds.map { chainId ->
            query {
                val hops = Delegations
                    .join(Agents, JoinType.LEFT, Delegations.fromAgentId, Agents.id)
                    .select { Delegations.chainId eq chainId }
                    .orderBy(Delegations.chainDepth, SortOrder.ASC)
                    .map { row ->
                        val toName = Agents.slice(Agents.name)
                            .select { Agents.id eq row[Delegations.toAgentId] }
                            .singleOrNull()?.get(Agents.name) ?: row[Delegations.toAgentId]
                        ChainHop(
                            depth        = row[Delegations.chainDepth],
                            delegationId = row[Delegations.id],
                            fromAgent    = row[Agents.name],
                            toAgent      = toName,
                            task         = row[Delegations.task],
                            status       = row[Delegations.status],
                            durationMs   = row[Delegations.durationMs]
                        )
                    }
                val overallStatus = when {
                    hops.any { it.status == "failed" || it.status == "timed_out" } -> "failed"
                    hops.all { it.status == "completed" } -> "completed"
                    else -> "running"
                }
                DelegationChain(
                    chainId         = chainId,
                    hops            = hops,
                    status          = overallStatus,
                    totalDurationMs = hops.sumOf { it.durationMs ?: 0 }
                )
            }
        }

        call.respond(mapOf("chains" to summaries, "count" to summaries.size))
    }
}
