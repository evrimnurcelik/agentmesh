package io.agentmesh.routes

import io.agentmesh.db.Agents
import io.agentmesh.db.DatabaseFactory.query
import io.agentmesh.db.McpServers
import io.agentmesh.models.*
import io.agentmesh.services.MatchingService
import io.agentmesh.util.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant

fun Route.mcpRoutes(minMatchScore: Int) {

    val httpClient = HttpClient(CIO) {
        engine { requestTimeout = 15_000 }
    }

    // ─────────────────────────────────────────
    // POST /agents/{agentId}/mcp-servers — register
    // ─────────────────────────────────────────
    post("/agents/{agentId}/mcp-servers") {
        val agentId = call.parameters["agentId"]!!
        val authAgentId = call.authenticatedAgentId()
            ?: return@post call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))
        if (authAgentId != agentId)
            return@post call.respond(HttpStatusCode.Forbidden,
                ApiError("forbidden", "forbidden", "Cannot modify another agent"))

        val req = call.receive<RegisterMcpServerRequest>()

        if (req.name.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("validation_error", "invalid_name", "name is required"))
            return@post
        }
        if (!req.url.startsWith("http")) {
            call.respond(HttpStatusCode.BadRequest, ApiError("validation_error", "invalid_url", "url must be a valid HTTP URL"))
            return@post
        }

        val mcpId = newId("mcp")
        query {
            McpServers.insert {
                it[id] = mcpId
                it[McpServers.agentId] = agentId
                it[name] = req.name
                it[url] = req.url
                it[authType] = req.authType
                it[authSecret] = req.authSecret
                it[tools] = "[]"
                it[createdAt] = Instant.now()
            }
        }

        call.respond(HttpStatusCode.Created, mapOf("mcp_id" to mcpId, "name" to req.name))
    }

    // ─────────────────────────────────────────
    // GET /agents/{agentId}/mcp-servers — list
    // ─────────────────────────────────────────
    get("/agents/{agentId}/mcp-servers") {
        val agentId = call.parameters["agentId"]!!
        val servers = query {
            McpServers.select { McpServers.agentId eq agentId }
                .orderBy(McpServers.createdAt, SortOrder.DESC)
                .map { row ->
                    mapOf(
                        "id" to row[McpServers.id],
                        "name" to row[McpServers.name],
                        "url" to row[McpServers.url],
                        "auth_type" to row[McpServers.authType],
                        "tools" to (runCatching { row[McpServers.tools].fromJson<List<String>>() }.getOrDefault(emptyList())),
                        "last_synced" to row[McpServers.lastSynced]?.toString(),
                        "created_at" to row[McpServers.createdAt].toString()
                    )
                }
        }
        call.respond(mapOf("servers" to servers, "count" to servers.size))
    }

    // ─────────────────────────────────────────
    // POST /agents/{agentId}/mcp-servers/{mcpId}/sync — trigger tool discovery
    // ─────────────────────────────────────────
    post("/agents/{agentId}/mcp-servers/{mcpId}/sync") {
        val agentId = call.parameters["agentId"]!!
        val mcpId = call.parameters["mcpId"]!!
        val authAgentId = call.authenticatedAgentId()
            ?: return@post call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))
        if (authAgentId != agentId)
            return@post call.respond(HttpStatusCode.Forbidden,
                ApiError("forbidden", "forbidden", "Cannot modify another agent"))

        val server = query {
            McpServers.select { (McpServers.id eq mcpId) and (McpServers.agentId eq agentId) }.singleOrNull()
        }
        if (server == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", "not_found", "MCP server not found"))
            return@post
        }

        // Call MCP server's tools/list endpoint
        val serverUrl = server[McpServers.url]
        val authType = server[McpServers.authType]
        val authSecret = server[McpServers.authSecret]

        try {
            val response = httpClient.post(serverUrl) {
                contentType(ContentType.Application.Json)
                setBody("""{"jsonrpc":"2.0","method":"tools/list","id":1}""")
                when (authType) {
                    "bearer" -> if (authSecret != null) header("Authorization", "Bearer $authSecret")
                    "api_key" -> if (authSecret != null) header("X-API-Key", authSecret)
                }
            }

            val body = response.bodyAsText()
            // Parse MCP response to extract tool names
            val toolNames = try {
                val toolsRegex = """"name"\s*:\s*"([^"]+)"""".toRegex()
                toolsRegex.findAll(body).map { it.groupValues[1] }.toList()
            } catch (_: Exception) {
                emptyList()
            }

            val now = Instant.now()
            query {
                McpServers.update({ McpServers.id eq mcpId }) {
                    it[tools] = toolNames.toJsonString()
                    it[lastSynced] = now
                }

                // Update agent's has[] to include discovered tools
                val currentHas = Agents.select { Agents.id eq agentId }.single()[Agents.has].toList()
                val updatedHas = (currentHas + toolNames).distinct()
                Agents.update({ Agents.id eq agentId }) {
                    it[has] = updatedHas.toTypedArray()
                    it[updatedAt] = now
                }
            }

            // Re-run matching
            MatchingService.scheduleMatching(agentId, minMatchScore)

            call.respond(mapOf(
                "mcp_id" to mcpId,
                "tools_discovered" to toolNames,
                "count" to toolNames.size,
                "synced_at" to now.toString()
            ))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadGateway, ApiError(
                "mcp_sync_failed", "sync_error",
                "Failed to sync with MCP server: ${e.message}"
            ))
        }
    }

    // ─────────────────────────────────────────
    // DELETE /agents/{agentId}/mcp-servers/{mcpId}
    // ─────────────────────────────────────────
    delete("/agents/{agentId}/mcp-servers/{mcpId}") {
        val agentId = call.parameters["agentId"]!!
        val mcpId = call.parameters["mcpId"]!!
        val authAgentId = call.authenticatedAgentId()
            ?: return@delete call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))
        if (authAgentId != agentId)
            return@delete call.respond(HttpStatusCode.Forbidden,
                ApiError("forbidden", "forbidden", "Cannot modify another agent"))

        val deleted = query {
            McpServers.deleteWhere { (id eq mcpId) and (McpServers.agentId eq agentId) }
        }

        if (deleted == 0) {
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", "not_found", "MCP server not found"))
        } else {
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
