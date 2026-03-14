package io.agentmesh.routes

import io.agentmesh.db.Agents
import io.agentmesh.db.DatabaseFactory.query
import io.agentmesh.models.AgentStats
import io.agentmesh.util.fromJson
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.select

fun Route.badgeRoutes() {

    // ─────────────────────────────────────────
    // GET /badge/{agentId} — dynamic SVG badge
    // ─────────────────────────────────────────
    get("/badge/{agentId}") {
        val agentId = call.parameters["agentId"]!!

        val agent = query {
            Agents.select { Agents.id eq agentId }.singleOrNull()
        }

        val svg = if (agent == null) {
            notFoundBadge()
        } else {
            val name = agent[Agents.name].take(20)
            val status = agent[Agents.status]
            val stats = agent[Agents.stats].fromJson<AgentStats>()
            val count = stats.delegationsSent
            val statusColor = when (status) {
                "online" -> "#22c55e"
                "idle"   -> "#f59e0b"
                else     -> "#555555"
            }
            agentBadge(name, statusColor, count)
        }

        call.response.header("Content-Type", "image/svg+xml")
        call.response.header("Cache-Control", "max-age=300, public")
        call.respondText(svg, ContentType.Image.SVG)
    }
}

private fun agentBadge(name: String, statusColor: String, count: Int): String {
    // Calculate widths dynamically
    val leftText = "AgentMesh"
    val rightText = "$name · $count"
    val leftWidth = 95
    val rightWidth = rightText.length * 7 + 30
    val totalWidth = leftWidth + rightWidth

    return """<svg xmlns="http://www.w3.org/2000/svg" width="$totalWidth" height="20">
  <rect width="$totalWidth" height="20" rx="3" fill="#0a0a0f"/>
  <rect width="$leftWidth" height="20" rx="3" fill="#1a3a2a"/>
  <rect x="${leftWidth - 3}" width="3" height="20" fill="#1a3a2a"/>
  <text x="8" y="14" font-family="monospace" font-size="11" fill="#63eba5">⬡ $leftText</text>
  <circle cx="${leftWidth + 8}" cy="10" r="4" fill="$statusColor"/>
  <text x="${leftWidth + 18}" y="14" font-family="monospace" font-size="11" fill="#ffffff">$rightText</text>
</svg>"""
}

private fun notFoundBadge(): String {
    return """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="20">
  <rect width="200" height="20" rx="3" fill="#333"/>
  <text x="8" y="14" font-family="monospace" font-size="11" fill="#888">⬡ AgentMesh · not found</text>
</svg>"""
}
