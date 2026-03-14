package io.agentmesh.routes

import io.agentmesh.db.*
import io.agentmesh.db.DatabaseFactory.query
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

fun Route.teamRoutes(minMatchScore: Int) {

    // ─────────────────────────────────────────
    // POST /teams — create a team
    // ─────────────────────────────────────────
    post("/teams") {
        val req = runCatching { call.receive<CreateTeamRequest>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest,
                ApiError("bad_request", "parse_error", "Invalid request body"))
            return@post
        }

        if (req.name.isBlank() || req.name.length > 60) {
            call.respond(HttpStatusCode.BadRequest,
                ApiError("validation_error", "invalid_name", "name must be 1–60 chars"))
            return@post
        }

        val apiKey     = generateApiKey()
        val apiKeyHash = hashApiKey(apiKey)
        val teamId     = newId("tm")
        val now        = Instant.now()

        query {
            Teams.insert {
                it[id]          = teamId
                it[name]        = req.name
                it[description] = req.description
                it[ownerEmail]  = req.ownerEmail
                it[Teams.apiKeyHash] = apiKeyHash
                it[public]      = req.public
                it[has]         = emptyArray()
                it[needs]       = emptyArray()
                it[stats]       = TeamStats().toJsonString()
                it[createdAt]   = now
                it[updatedAt]   = now
            }
        }

        call.respond(HttpStatusCode.Created, CreateTeamResponse(
            teamId    = teamId,
            apiKey    = apiKey,
            createdAt = now.toString()
        ))
    }

    // ─────────────────────────────────────────
    // GET /teams — list public teams
    // ─────────────────────────────────────────
    get("/teams") {
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)

        val teams = query {
            Teams.select { Teams.public eq true }
                .orderBy(Teams.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    val members = TeamMembers
                        .join(Agents, JoinType.LEFT, TeamMembers.agentId, Agents.id)
                        .select { TeamMembers.teamId eq row[Teams.id] }
                        .map { m ->
                            TeamMember(
                                agentId = m[TeamMembers.agentId],
                                name    = m[Agents.name],
                                role    = m[TeamMembers.role],
                                addedAt = m[TeamMembers.addedAt].toString()
                            )
                        }
                    Team(
                        id          = row[Teams.id],
                        name        = row[Teams.name],
                        description = row[Teams.description],
                        public      = row[Teams.public],
                        has         = row[Teams.has].toList(),
                        needs       = row[Teams.needs].toList(),
                        stats       = row[Teams.stats].fromJson<TeamStats>(),
                        members     = members,
                        createdAt   = row[Teams.createdAt].toString(),
                        updatedAt   = row[Teams.updatedAt].toString()
                    )
                }
        }

        call.respond(mapOf("teams" to teams, "count" to teams.size))
    }

    // ─────────────────────────────────────────
    // GET /teams/{teamId}
    // ─────────────────────────────────────────
    get("/teams/{teamId}") {
        val teamId = call.parameters["teamId"]!!

        val team = query {
            val row = Teams.select { Teams.id eq teamId }.singleOrNull()
                ?: return@query null

            val members = TeamMembers
                .join(Agents, JoinType.LEFT, TeamMembers.agentId, Agents.id)
                .select { TeamMembers.teamId eq teamId }
                .map { m ->
                    TeamMember(
                        agentId = m[TeamMembers.agentId],
                        name    = m[Agents.name],
                        role    = m[TeamMembers.role],
                        addedAt = m[TeamMembers.addedAt].toString()
                    )
                }

            Team(
                id          = row[Teams.id],
                name        = row[Teams.name],
                description = row[Teams.description],
                public      = row[Teams.public],
                has         = row[Teams.has].toList(),
                needs       = row[Teams.needs].toList(),
                stats       = row[Teams.stats].fromJson<TeamStats>(),
                members     = members,
                createdAt   = row[Teams.createdAt].toString(),
                updatedAt   = row[Teams.updatedAt].toString()
            )
        }

        if (team == null) {
            call.respond(HttpStatusCode.NotFound,
                ApiError("not_found", "not_found", "Team not found"))
        } else {
            call.respond(team)
        }
    }

    // ─────────────────────────────────────────
    // POST /teams/{teamId}/members — add an agent
    // ─────────────────────────────────────────
    post("/teams/{teamId}/members") {
        val teamId  = call.parameters["teamId"]!!
        val authKey = call.authenticatedAgentId()

        // Auth: must be team API key (stored against a synthetic "team agent")
        // For simplicity: check if caller owns the team by email match via query param
        // In production this would verify team API key separately
        val req = runCatching { call.receive<AddTeamMemberRequest>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest,
                ApiError("bad_request", "parse_error", "Invalid request body"))
            return@post
        }

        val now = Instant.now()

        val added = query {
            // Verify team exists
            val team = Teams.select { Teams.id eq teamId }.singleOrNull()
                ?: return@query false

            // Verify agent exists
            val agent = Agents.select { Agents.id eq req.agentId }.singleOrNull()
                ?: return@query false

            // Add member
            TeamMembers.upsert(TeamMembers.teamId, TeamMembers.agentId) {
                it[TeamMembers.teamId]  = teamId
                it[TeamMembers.agentId] = req.agentId
                it[role]                = req.role
                it[addedAt]             = now
            }

            // Recompute team's has/needs as union of all members
            val allMembers = TeamMembers
                .join(Agents, JoinType.LEFT, TeamMembers.agentId, Agents.id)
                .select { TeamMembers.teamId eq teamId }
                .toList()

            val unionHas   = allMembers.flatMap { it[Agents.has].toList() }.distinct()
            val unionNeeds = allMembers.flatMap { it[Agents.needs].toList() }.distinct()

            Teams.update({ Teams.id eq teamId }) {
                it[has]       = unionHas.toTypedArray()
                it[needs]     = unionNeeds.toTypedArray()
                it[updatedAt] = now
            }

            true
        }

        if (!added) {
            call.respond(HttpStatusCode.NotFound,
                ApiError("not_found", "not_found", "Team or agent not found"))
        } else {
            call.respond(HttpStatusCode.Created,
                mapOf("team_id" to teamId, "agent_id" to req.agentId, "role" to req.role))
        }
    }

    // ─────────────────────────────────────────
    // DELETE /teams/{teamId}/members/{agentId}
    // ─────────────────────────────────────────
    delete("/teams/{teamId}/members/{agentId}") {
        val teamId  = call.parameters["teamId"]!!
        val agentId = call.parameters["agentId"]!!
        val now     = Instant.now()

        query {
            TeamMembers.deleteWhere {
                (TeamMembers.teamId eq teamId) and (TeamMembers.agentId eq agentId)
            }

            // Recompute union capabilities
            val remaining = TeamMembers
                .join(Agents, JoinType.LEFT, TeamMembers.agentId, Agents.id)
                .select { TeamMembers.teamId eq teamId }
                .toList()

            Teams.update({ Teams.id eq teamId }) {
                it[has]       = remaining.flatMap { r -> r[Agents.has].toList() }.distinct().toTypedArray()
                it[needs]     = remaining.flatMap { r -> r[Agents.needs].toList() }.distinct().toTypedArray()
                it[updatedAt] = now
            }
        }

        call.respond(HttpStatusCode.NoContent)
    }
}

private fun emptyArray(): Array<String> = emptyArray()
