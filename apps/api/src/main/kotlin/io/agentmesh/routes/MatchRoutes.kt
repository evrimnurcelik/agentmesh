package io.agentmesh.routes

import io.agentmesh.db.Agents
import io.agentmesh.db.Capabilities
import io.agentmesh.db.DatabaseFactory.query
import io.agentmesh.db.Matches
import io.agentmesh.db.SlaViolations
import io.agentmesh.models.*
import io.agentmesh.services.MatchingService
import io.agentmesh.util.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import java.time.Instant

fun Route.matchRoutes() {

    // ─────────────────────────────────────────
    // GET /matches — list matches for authed agent
    // ─────────────────────────────────────────
    get("/matches") {
        val agentId = call.authenticatedAgentId()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "unauthorized", "Invalid API key"))

        val minScore = call.request.queryParameters["min_score"]?.toIntOrNull() ?: 50
        val status   = call.request.queryParameters["status"] ?: "all"
        val limit    = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)

        val matches = query {
            var q = Matches.select {
                (Matches.agentAId eq agentId) or (Matches.agentBId eq agentId)
            }.andWhere { Matches.score greaterEq minScore }

            if (status != "all") q = q.andWhere { Matches.status eq status }

            q.orderBy(Matches.score, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    val counterpartId = if (row[Matches.agentAId] == agentId)
                        row[Matches.agentBId] else row[Matches.agentAId]

                    val counterpart = Agents.select { Agents.id eq counterpartId }
                        .singleOrNull()

                    mapOf(
                        "match_id"       to row[Matches.id],
                        "agent"          to counterpart?.let { a ->
                            mapOf(
                                "agent_id"    to a[Agents.id],
                                "name"        to a[Agents.name],
                                "owner"       to a[Agents.ownerEmail].substringBefore("@"),
                                "framework"   to a[Agents.framework]
                            )
                        },
                        "score"          to row[Matches.score],
                        "score_breakdown" to row[Matches.scoreBreakdown].fromJson<ScoreBreakdown>(),
                        "reason"         to row[Matches.reason],
                        "covering_needs" to row[Matches.coveringNeeds].toList(),
                        "status"         to row[Matches.status],
                        "created_at"     to row[Matches.createdAt].toString()
                    )
                }
        }

        call.respond(mapOf("matches" to matches, "total" to matches.size))
    }

    // ─────────────────────────────────────────
    // POST /matches/{matchId}/approve
    // ─────────────────────────────────────────
    post("/matches/{matchId}/approve") {
        val matchId = call.parameters["matchId"]!!
        val agentId = call.authenticatedAgentId()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "unauthorized", "Invalid API key"))

        // v0.4: Accept optional SLA
        val approveReq = runCatching { call.receive<ApproveMatchWithSlaRequest>() }.getOrDefault(ApproveMatchWithSlaRequest())

        val result = query {
            val match = Matches.select { Matches.id eq matchId }.singleOrNull()
                ?: return@query null to "match_not_found"

            val isA = match[Matches.agentAId] == agentId
            val isB = match[Matches.agentBId] == agentId
            if (!isA && !isB) return@query null to "forbidden"

            val alreadyApprovedByThis = (isA && match[Matches.approvedByA]) || (isB && match[Matches.approvedByB])
            if (alreadyApprovedByThis) return@query null to "already_approved"

            val now = Instant.now()

            // Mark this agent's approval
            Matches.update({ Matches.id eq matchId }) { row ->
                if (isA) row[approvedByA] = true else row[approvedByB] = true
                row[updatedAt] = now
            }

            // Re-fetch to check if both sides now approved
            val updated = Matches.select { Matches.id eq matchId }.single()
            val bothApproved = updated[Matches.approvedByA] && updated[Matches.approvedByB]

            if (bothApproved) {
                // Build contract
                val agentA = Agents.select { Agents.id eq updated[Matches.agentAId] }.single()
                val agentB = Agents.select { Agents.id eq updated[Matches.agentBId] }.single()
                val capMap = Capabilities
                    .selectAll()
                    .associate { it[Capabilities.id] to it[Capabilities.taskTypes].toList() }

                val contract = MatchingService.buildContract(agentA, agentB, capMap)

                Matches.update({ Matches.id eq matchId }) { row ->
                    row[status]   = "active"
                    row[Matches.contract] = contract.toJsonString()
                    if (approveReq.sla != null) {
                        row[Matches.sla] = approveReq.sla.toJsonString()
                    }
                    row[updatedAt] = now
                }

                mapOf(
                    "match_id"           to matchId,
                    "status"             to "active",
                    "delegation_enabled" to true,
                    "contract"           to contract
                ) to null
            } else {
                mapOf(
                    "match_id" to matchId,
                    "status"   to "pending_counterpart",
                    "message"  to "Waiting for the other agent's owner to approve"
                ) to null
            }
        }

        val (response, errorCode) = result
        when (errorCode) {
            "match_not_found" -> call.respond(HttpStatusCode.NotFound,   ApiError("match_not_found", "not_found", "Match not found"))
            "forbidden"       -> call.respond(HttpStatusCode.Forbidden,  ApiError("forbidden", "forbidden", "Not a party to this match"))
            "already_approved"-> call.respond(HttpStatusCode.Conflict,   ApiError("conflict", "already_approved", "Already approved by this agent"))
            null              -> call.respond(HttpStatusCode.OK, response!!)
            else              -> call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "error", "Unexpected error"))
        }
    }

    // ─────────────────────────────────────────
    // GET /matches/{matchId}/sla — SLA compliance stats (v0.4)
    // ─────────────────────────────────────────
    get("/matches/{matchId}/sla") {
        val matchId = call.parameters["matchId"]!!

        val match = query { Matches.select { Matches.id eq matchId }.singleOrNull() }
        if (match == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", "not_found", "Match not found"))
            return@get
        }

        val slaStr = match[Matches.sla]
        val violations = query {
            SlaViolations.select { SlaViolations.matchId eq matchId }
                .orderBy(SlaViolations.createdAt, SortOrder.DESC)
                .limit(50)
                .map { row ->
                    mapOf(
                        "id" to row[SlaViolations.id],
                        "violation_type" to row[SlaViolations.violationType],
                        "measured_value" to row[SlaViolations.measuredValue].toDouble(),
                        "threshold" to row[SlaViolations.threshold].toDouble(),
                        "window_start" to row[SlaViolations.windowStart].toString(),
                        "window_end" to row[SlaViolations.windowEnd].toString(),
                        "resolved" to row[SlaViolations.resolved],
                        "created_at" to row[SlaViolations.createdAt].toString()
                    )
                }
        }

        call.respond(mapOf(
            "match_id" to matchId,
            "sla" to slaStr?.fromJson<SlaRequirement>(),
            "violations" to violations,
            "violation_count" to violations.size
        ))
    }

    // ─────────────────────────────────────────
    // GET /agents/{agentId}/sla-violations — all violations for agent (v0.4)
    // ─────────────────────────────────────────
    get("/agents/{agentId}/sla-violations") {
        val agentId = call.parameters["agentId"]!!
        val violations = query {
            SlaViolations.select { SlaViolations.violatingAgentId eq agentId }
                .orderBy(SlaViolations.createdAt, SortOrder.DESC)
                .limit(100)
                .map { row ->
                    mapOf(
                        "id" to row[SlaViolations.id],
                        "match_id" to row[SlaViolations.matchId],
                        "violation_type" to row[SlaViolations.violationType],
                        "measured_value" to row[SlaViolations.measuredValue].toDouble(),
                        "threshold" to row[SlaViolations.threshold].toDouble(),
                        "resolved" to row[SlaViolations.resolved],
                        "created_at" to row[SlaViolations.createdAt].toString()
                    )
                }
        }
        call.respond(mapOf("violations" to violations, "count" to violations.size))
    }

    // ─────────────────────────────────────────
    // POST /matches/{matchId}/dismiss
    // ─────────────────────────────────────────
    post("/matches/{matchId}/dismiss") {
        val matchId = call.parameters["matchId"]!!
        val agentId = call.authenticatedAgentId()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "unauthorized", "Invalid API key"))

        val ok = query {
            val match = Matches.select { Matches.id eq matchId }.singleOrNull() ?: return@query false
            if (match[Matches.agentAId] != agentId && match[Matches.agentBId] != agentId) return@query false

            Matches.update({ Matches.id eq matchId }) {
                it[status]         = "dismissed"
                it[dismissedUntil] = Instant.now().plusSeconds(30 * 24 * 3600)
                it[updatedAt]      = Instant.now()
            }
            true
        }

        if (!ok) {
            call.respond(HttpStatusCode.NotFound, ApiError("match_not_found", "not_found", "Match not found or not accessible"))
        } else {
            call.respond(mapOf("match_id" to matchId, "status" to "dismissed"))
        }
    }
}
