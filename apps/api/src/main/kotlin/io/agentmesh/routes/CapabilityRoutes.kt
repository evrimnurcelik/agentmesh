package io.agentmesh.routes

import io.agentmesh.db.Capabilities
import io.agentmesh.db.DatabaseFactory.query
import io.agentmesh.models.Capability
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.selectAll

fun Route.capabilityRoutes() {

    get("/capabilities") {
        val capabilities = query {
            Capabilities.selectAll()
                .orderBy(Capabilities.category)
                .orderBy(Capabilities.label)
                .map { row ->
                    Capability(
                        id        = row[Capabilities.id],
                        label     = row[Capabilities.label],
                        category  = row[Capabilities.category],
                        taskTypes = row[Capabilities.taskTypes].toList(),
                        color     = row[Capabilities.color]
                    )
                }
        }

        val grouped = capabilities.groupBy { it.category }

        call.respond(mapOf(
            "capabilities" to capabilities,
            "grouped"      to grouped,
            "count"        to capabilities.size
        ))
    }
}
