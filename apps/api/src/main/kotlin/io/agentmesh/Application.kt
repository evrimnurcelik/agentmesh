package io.agentmesh

import com.typesafe.config.ConfigFactory
import io.agentmesh.db.DatabaseFactory
import io.agentmesh.plugins.*
import io.agentmesh.routes.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    embeddedServer(Netty, port = System.getenv("PORT")?.toInt() ?: 8080) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    val config              = ConfigFactory.load()
    val webhookSecret       = config.getString("agentmesh.webhookSecret")
    val adminSecret         = config.getString("agentmesh.adminSecret")
    val stripeWebhookSecret = config.getString("agentmesh.stripeWebhookSecret")
    val minMatchScore       = config.getInt("agentmesh.matchMinScore")

    DatabaseFactory.init()

    configureSerialization()
    configureCors()
    configureStatusPages()
    configureLogging()

    routing {
        get("/health") {
            call.respond(mapOf(
                "status"   to "ok",
                "version"  to "0.3.0",
                "service"  to "agentmesh",
                "features" to listOf(
                    "agent_registry",
                    "capability_matching",
                    "trust_tiers",         // v0.2
                    "delegation_chains",   // v0.2
                    "capability_schemas",  // v0.2
                    "agent_teams",         // v0.3
                    "billing",             // v0.3
                    "fallback_escalation"  // v0.3
                )
            ))
        }

        // All routes registered at both root and /v1 prefix
        listOf("", "/v1").forEach { prefix ->
            route(prefix) {
                agentRoutes(minMatchScore)
                matchRoutes()
                delegationRoutes(webhookSecret)
                capabilityRoutes()
                webhookRoutes(webhookSecret)
                chainRoutes()                           // v0.2
                trustRoutes(adminSecret)                // v0.2
                teamRoutes(minMatchScore)               // v0.3
                billingRoutes(stripeWebhookSecret)      // v0.3
            }
        }
    }
}
