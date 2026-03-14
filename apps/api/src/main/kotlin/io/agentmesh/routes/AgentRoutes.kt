package io.agentmesh.routes

import io.agentmesh.db.Agents
import io.agentmesh.db.Capabilities
import io.agentmesh.db.DatabaseFactory.query
import io.agentmesh.db.Matches
import io.agentmesh.models.*
import io.agentmesh.services.MatchingService
import io.agentmesh.util.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant

fun Route.agentRoutes(minMatchScore: Int) {

    // ─────────────────────────────────────────
    // POST /agents — register
    // ─────────────────────────────────────────
    post("/agents") {
        val req = runCatching { call.receive<RegisterAgentRequest>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "parse_error", "Invalid JSON body"))
            return@post
        }

        // Validate
        if (req.name.isBlank() || req.name.length > 50) {
            call.respond(HttpStatusCode.BadRequest, ApiError("validation_error", "invalid_name", "name must be 1–50 chars"))
            return@post
        }
        if (req.description.isBlank() || req.description.length > 300) {
            call.respond(HttpStatusCode.BadRequest, ApiError("validation_error", "invalid_description", "description must be 1–300 chars"))
            return@post
        }
        if (!req.ownerEmail.contains("@")) {
            call.respond(HttpStatusCode.BadRequest, ApiError("validation_error", "invalid_email", "Invalid owner_email"))
            return@post
        }
        if (!req.webhookUrl.startsWith("http")) {
            call.respond(HttpStatusCode.BadRequest, ApiError("validation_error", "invalid_webhook", "webhook_url must be a valid URL"))
            return@post
        }
        if (req.framework !in listOf("openclaw", "langchain", "autogen", "crewai", "custom")) {
            call.respond(HttpStatusCode.BadRequest, ApiError("validation_error", "invalid_framework", "Unknown framework: ${req.framework}"))
            return@post
        }

        // Validate capability IDs
        val allTools = (req.has + req.needs).distinct()
        if (allTools.isNotEmpty()) {
            val unknownTools = query {
                val known = Capabilities.slice(Capabilities.id).select { Capabilities.id inList allTools }
                    .map { it[Capabilities.id] }.toSet()
                allTools.filter { it !in known }
            }
            if (unknownTools.isNotEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("validation_error", "unknown_capabilities",
                    "Unknown tool IDs: ${unknownTools.joinToString(", ")}"))
                return@post
            }
        }

        val apiKey     = generateApiKey()
        val apiKeyHash = hashApiKey(apiKey)
        val agentId    = newId("ag")
        val now        = Instant.now()
        val statsJson  = AgentStats().toJsonString()

        query {
            Agents.insert {
                it[id]          = agentId
                it[name]        = req.name
                it[description] = req.description
                it[framework]   = req.framework
                it[has]         = req.has.toTypedArray()
                it[needs]       = req.needs.toTypedArray()
                it[ownerEmail]  = req.ownerEmail
                it[webhookUrl]  = req.webhookUrl
                it[Agents.apiKeyHash] = apiKeyHash
                it[public]      = req.public
                it[status]      = "offline"
                it[stats]       = statsJson
                it[createdAt]   = now
                it[updatedAt]   = now
            }
        }

        // Fire-and-forget matching
        MatchingService.scheduleMatching(agentId, minMatchScore)

        call.respond(
            HttpStatusCode.Created,
            RegisterAgentResponse(
                agentId        = agentId,
                apiKey         = apiKey,
                createdAt      = now.toString(),
                matchScoreReady = false
            )
        )
    }

    // ─────────────────────────────────────────
    // GET /agents — list public agents
    // ─────────────────────────────────────────
    get("/agents") {
        val limit     = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
        val framework = call.request.queryParameters["framework"]
        val hasParam  = call.request.queryParameters["has"]?.split(",")
        val needsParam = call.request.queryParameters["needs"]?.split(",")

        val agents = query {
            var q = Agents.select { Agents.public eq true }
            if (framework != null) q = q.andWhere { Agents.framework eq framework }
            // Array overlap filters applied post-fetch for simplicity (use raw SQL for prod scale)
            q.orderBy(Agents.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { row -> row.toPublicAgent() }
                .let { list ->
                    var filtered = list
                    if (hasParam != null) filtered = filtered.filter { a -> a.has.any { it in hasParam } }
                    if (needsParam != null) filtered = filtered.filter { a -> a.needs.any { it in needsParam } }
                    filtered
                }
        }

        call.respond(mapOf("agents" to agents, "count" to agents.size))
    }

    // ─────────────────────────────────────────
    // GET /agents/{agentId}
    // ─────────────────────────────────────────
    get("/agents/{agentId}") {
        val agentId = call.parameters["agentId"]!!
        val agent = query {
            Agents.select { (Agents.id eq agentId) and (Agents.public eq true) }
                .singleOrNull()?.toPublicAgent()
        }

        if (agent == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("agent_not_found", "not_found", "Agent not found"))
            return@get
        }

        val matchedWith = query {
            Matches.slice(Matches.agentBId)
                .select { (Matches.agentAId eq agentId) and (Matches.status eq "active") }
                .map { it[Matches.agentBId] }
        }

        call.respond(agent.copy(matchedWith = matchedWith))
    }

    // ─────────────────────────────────────────
    // PATCH /agents/{agentId}
    // ─────────────────────────────────────────
    patch("/agents/{agentId}") {
        val agentId      = call.parameters["agentId"]!!
        val authAgentId  = call.authenticatedAgentId()
            ?: return@patch call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "unauthorized", "Invalid API key"))
        if (authAgentId != agentId)
            return@patch call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "forbidden", "Cannot modify another agent"))

        val req = call.receive<PatchAgentRequest>()
        val now = Instant.now()

        query {
            Agents.update({ Agents.id eq agentId }) { row ->
                req.name?.let        { row[name]        = it }
                req.description?.let { row[description] = it }
                req.has?.let         { row[has]         = it.toTypedArray() }
                req.needs?.let       { row[needs]       = it.toTypedArray() }
                req.webhookUrl?.let  { row[webhookUrl]  = it }
                req.public?.let      { row[public]      = it }
                req.status?.let      { s ->
                    row[status] = s
                    if (s == "online") row[lastPing] = now
                }
                row[updatedAt] = now
            }
        }

        // Re-run matching if tools changed
        if (req.has != null || req.needs != null) {
            MatchingService.scheduleMatching(agentId, minMatchScore)
        }

        val updated = query {
            Agents.select { Agents.id eq agentId }.single().toPublicAgent()
        }
        call.respond(updated)
    }

    // ─────────────────────────────────────────
    // DELETE /agents/{agentId}
    // ─────────────────────────────────────────
    delete("/agents/{agentId}") {
        val agentId     = call.parameters["agentId"]!!
        val authAgentId = call.authenticatedAgentId()
            ?: return@delete call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "unauthorized", "Invalid API key"))
        if (authAgentId != agentId)
            return@delete call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "forbidden", "Cannot delete another agent"))

        query { Agents.deleteWhere { id eq agentId } }
        call.respond(HttpStatusCode.NoContent)
    }

    // ─────────────────────────────────────────
    // PATCH /agents/{agentId}/status — heartbeat
    // ─────────────────────────────────────────
    patch("/agents/{agentId}/status") {
        val agentId     = call.parameters["agentId"]!!
        val authAgentId = call.authenticatedAgentId()
            ?: return@patch call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "unauthorized", "Invalid API key"))
        if (authAgentId != agentId)
            return@patch call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "forbidden", "Forbidden"))

        val body   = runCatching { call.receive<StatusUpdate>() }.getOrDefault(StatusUpdate("online"))
        val status = if (body.status in listOf("online", "idle", "offline")) body.status else "online"
        val now    = Instant.now()

        query {
            Agents.update({ Agents.id eq agentId }) {
                it[Agents.status]  = status
                it[lastPing]  = now
                it[updatedAt] = now
            }
        }

        call.respond(mapOf("id" to agentId, "status" to status, "last_ping" to now.toString()))
    }
}

// ─── Row mapper ───────────────────────────────────────────────
private fun ResultRow.toPublicAgent() = PublicAgent(
    id          = this[Agents.id],
    name        = this[Agents.name],
    description = this[Agents.description],
    framework   = this[Agents.framework],
    has         = this[Agents.has].toList(),
    needs       = this[Agents.needs].toList(),
    public      = this[Agents.public],
    status      = this[Agents.status],
    stats       = this[Agents.stats].fromJson<AgentStats>(),
    createdAt   = this[Agents.createdAt].toString(),
    updatedAt   = this[Agents.updatedAt].toString()
)
