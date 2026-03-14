package io.agentmesh.routes

import io.agentmesh.db.*
import io.agentmesh.db.DatabaseFactory.query
import io.agentmesh.models.*
import io.agentmesh.util.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant

fun Route.orgRoutes() {

    // ─────────────────────────────────────────
    // POST /orgs — create org
    // ─────────────────────────────────────────
    post("/orgs") {
        val req = call.receive<CreateOrgRequest>()

        if (req.name.isBlank() || req.name.length > 50) {
            call.respond(HttpStatusCode.BadRequest, ApiError("validation_error", "invalid_name", "name must be 1-50 chars"))
            return@post
        }
        if (req.slug.isBlank() || !req.slug.matches(Regex("^[a-z0-9-]+$"))) {
            call.respond(HttpStatusCode.BadRequest, ApiError("validation_error", "invalid_slug", "slug must be lowercase alphanumeric with hyphens"))
            return@post
        }

        // Check slug uniqueness
        val existing = query {
            Orgs.select { Orgs.slug eq req.slug }.singleOrNull()
        }
        if (existing != null) {
            call.respond(HttpStatusCode.Conflict, ApiError("conflict", "slug_taken", "Slug '${req.slug}' is already taken"))
            return@post
        }

        val orgId = newId("org")
        val now = Instant.now()

        // Get creator's email from auth
        val agentId = call.authenticatedAgentId()
        val ownerEmail = if (agentId != null) {
            query { Agents.select { Agents.id eq agentId }.singleOrNull()?.get(Agents.ownerEmail) }
        } else null

        query {
            Orgs.insert {
                it[id] = orgId
                it[name] = req.name
                it[slug] = req.slug
                it[plan] = "free"
                it[createdAt] = now
            }

            // Add creator as owner
            if (ownerEmail != null) {
                OrgMembers.insert {
                    it[OrgMembers.orgId] = orgId
                    it[email] = ownerEmail
                    it[role] = "owner"
                    it[invitedAt] = now
                    it[joinedAt] = now
                }
            }
        }

        call.respond(HttpStatusCode.Created, mapOf("org_id" to orgId, "slug" to req.slug, "created_at" to now.toString()))
    }

    // ─────────────────────────────────────────
    // GET /orgs/{orgId}
    // ─────────────────────────────────────────
    get("/orgs/{orgId}") {
        val orgId = call.parameters["orgId"]!!
        val org = query {
            Orgs.select { Orgs.id eq orgId }.singleOrNull()
        }
        if (org == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", "not_found", "Org not found"))
            return@get
        }

        val members = query {
            OrgMembers.select { OrgMembers.orgId eq orgId }.map { row ->
                mapOf(
                    "email" to row[OrgMembers.email],
                    "role" to row[OrgMembers.role],
                    "invited_at" to row[OrgMembers.invitedAt].toString(),
                    "joined_at" to row[OrgMembers.joinedAt]?.toString()
                )
            }
        }

        call.respond(mapOf(
            "id" to org[Orgs.id],
            "name" to org[Orgs.name],
            "slug" to org[Orgs.slug],
            "plan" to org[Orgs.plan],
            "members" to members,
            "created_at" to org[Orgs.createdAt].toString()
        ))
    }

    // ─────────────────────────────────────────
    // GET /orgs/{orgId}/agents
    // ─────────────────────────────────────────
    get("/orgs/{orgId}/agents") {
        val orgId = call.parameters["orgId"]!!
        val agents = query {
            Agents.select { Agents.orgId eq orgId }
                .map { row ->
                    mapOf(
                        "id" to row[Agents.id],
                        "name" to row[Agents.name],
                        "framework" to row[Agents.framework],
                        "status" to row[Agents.status],
                        "has" to row[Agents.has].toList(),
                        "needs" to row[Agents.needs].toList()
                    )
                }
        }
        call.respond(mapOf("agents" to agents, "count" to agents.size))
    }

    // ─────────────────────────────────────────
    // GET /orgs/{orgId}/delegations
    // ─────────────────────────────────────────
    get("/orgs/{orgId}/delegations") {
        val orgId = call.parameters["orgId"]!!
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)

        val orgAgentIds = query {
            Agents.slice(Agents.id).select { Agents.orgId eq orgId }.map { it[Agents.id] }
        }

        if (orgAgentIds.isEmpty()) {
            call.respond(mapOf("delegations" to emptyList<Any>(), "count" to 0))
            return@get
        }

        val delegations = query {
            Delegations.select {
                (Delegations.fromAgentId inList orgAgentIds) or (Delegations.toAgentId inList orgAgentIds)
            }.orderBy(Delegations.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    mapOf(
                        "id" to row[Delegations.id],
                        "from_agent_id" to row[Delegations.fromAgentId],
                        "to_agent_id" to row[Delegations.toAgentId],
                        "task" to row[Delegations.task],
                        "status" to row[Delegations.status],
                        "duration_ms" to row[Delegations.durationMs],
                        "created_at" to row[Delegations.createdAt].toString()
                    )
                }
        }

        call.respond(mapOf("delegations" to delegations, "count" to delegations.size))
    }

    // ─────────────────────────────────────────
    // POST /orgs/{orgId}/members — invite member
    // ─────────────────────────────────────────
    post("/orgs/{orgId}/members") {
        val orgId = call.parameters["orgId"]!!
        val req = call.receive<InviteMemberRequest>()

        if (!req.email.contains("@")) {
            call.respond(HttpStatusCode.BadRequest, ApiError("validation_error", "invalid_email", "Invalid email"))
            return@post
        }
        if (req.role !in listOf("owner", "admin", "member", "viewer")) {
            call.respond(HttpStatusCode.BadRequest, ApiError("validation_error", "invalid_role", "Role must be owner|admin|member|viewer"))
            return@post
        }

        query {
            OrgMembers.insert {
                it[OrgMembers.orgId] = orgId
                it[email] = req.email
                it[role] = req.role
                it[invitedAt] = Instant.now()
            }
        }

        call.respond(HttpStatusCode.Created, mapOf("org_id" to orgId, "email" to req.email, "role" to req.role))
    }

    // ─────────────────────────────────────────
    // DELETE /orgs/{orgId}/members/{email}
    // ─────────────────────────────────────────
    delete("/orgs/{orgId}/members/{email}") {
        val orgId = call.parameters["orgId"]!!
        val email = call.parameters["email"]!!

        val deleted = query {
            OrgMembers.deleteWhere { (OrgMembers.orgId eq orgId) and (OrgMembers.email eq email) }
        }

        if (deleted == 0) {
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", "not_found", "Member not found"))
        } else {
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // ─────────────────────────────────────────
    // POST /orgs/{orgId}/keys — create scoped API key
    // ─────────────────────────────────────────
    post("/orgs/{orgId}/keys") {
        val orgId = call.parameters["orgId"]!!
        val req = call.receive<CreateOrgKeyRequest>()

        val apiKey = generateApiKey().replace("amk_live_", "oak_live_")
        val keyHash = hashApiKey(apiKey)
        val keyId = newId("oak")

        query {
            OrgApiKeys.insert {
                it[id] = keyId
                it[OrgApiKeys.orgId] = orgId
                it[name] = req.name
                it[apiKeyHash] = keyHash
                it[scopes] = req.scopes.toTypedArray()
                it[createdAt] = Instant.now()
            }
        }

        call.respond(HttpStatusCode.Created, mapOf(
            "key_id" to keyId,
            "api_key" to apiKey,
            "name" to req.name,
            "scopes" to req.scopes
        ))
    }

    // ─────────────────────────────────────────
    // DELETE /orgs/{orgId}/keys/{keyId}
    // ─────────────────────────────────────────
    delete("/orgs/{orgId}/keys/{keyId}") {
        val keyId = call.parameters["keyId"]!!

        val deleted = query {
            OrgApiKeys.deleteWhere { id eq keyId }
        }

        if (deleted == 0) {
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", "not_found", "Key not found"))
        } else {
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
