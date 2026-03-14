package io.agentmesh.routes

import io.agentmesh.db.Agents
import io.agentmesh.db.DatabaseFactory.query
import io.agentmesh.models.ApiError
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.update
import java.time.Instant

@Serializable
data class VerifyAgentRequest(
    val agent_id: String,
    val tier: String  // "verified" or "private"
)

fun Route.trustRoutes(adminSecret: String) {

    // ─────────────────────────────────────────
    // POST /admin/verify — elevate agent trust tier
    // Requires X-Admin-Secret header
    // ─────────────────────────────────────────
    post("/admin/verify") {
        val secret = call.request.header("X-Admin-Secret")
        if (secret != adminSecret) {
            call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid admin secret"))
            return@post
        }

        val req = runCatching { call.receive<VerifyAgentRequest>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest,
                ApiError("bad_request", "parse_error", "Invalid request body"))
            return@post
        }

        if (req.tier !in listOf("verified", "private", "public")) {
            call.respond(HttpStatusCode.BadRequest,
                ApiError("validation_error", "invalid_tier", "tier must be public, verified, or private"))
            return@post
        }

        val updated = query {
            Agents.update({ Agents.id eq req.agent_id }) {
                it[trustTier]  = req.tier
                it[verifiedAt] = if (req.tier == "verified") Instant.now() else null
                it[updatedAt]  = Instant.now()
            }
        }

        if (updated == 0) {
            call.respond(HttpStatusCode.NotFound,
                ApiError("not_found", "not_found", "Agent not found"))
        } else {
            call.respond(mapOf(
                "agent_id"    to req.agent_id,
                "trust_tier"  to req.tier,
                "verified_at" to if (req.tier == "verified") Instant.now().toString() else null
            ))
        }
    }

    // ─────────────────────────────────────────
    // GET /agents?trust_tier=verified
    // Already handled in AgentRoutes — trust_tier is just a filter param
    // This route documents the tier semantics
    // ─────────────────────────────────────────
    get("/trust/tiers") {
        call.respond(mapOf(
            "tiers" to listOf(
                mapOf("tier" to "public",   "description" to "Default. Visible to all agents in the registry."),
                mapOf("tier" to "verified", "description" to "Badge shown on profile. Vetted by AgentMesh team. Higher match priority."),
                mapOf("tier" to "private",  "description" to "Hidden from public registry. Only discoverable via direct agent ID.")
            )
        ))
    }
}
