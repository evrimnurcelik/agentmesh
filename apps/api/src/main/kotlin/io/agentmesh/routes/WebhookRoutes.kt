package io.agentmesh.routes

import io.agentmesh.db.Agents
import io.agentmesh.db.BillingTransactions
import io.agentmesh.db.DatabaseFactory.query
import io.agentmesh.db.Delegations
import io.agentmesh.db.Matches
import io.agentmesh.models.*
import io.agentmesh.services.WebhookService
import io.agentmesh.util.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import java.time.Instant

@Serializable
data class DelegationResult(
    val delegation_id: String,
    val status: String,
    val output: Map<String, @Serializable Any?>? = null,
    val error: DelegationError? = null
)

fun Route.webhookRoutes(webhookSecret: String) {

    val scope = CoroutineScope(Dispatchers.IO)

    // ─────────────────────────────────────────
    // POST /webhooks/result
    // Executor agents POST results here.
    // Handles: completion, failure, fallback escalation (v0.3), billing (v0.3)
    // ─────────────────────────────────────────
    post("/webhooks/result") {
        val result = runCatching { call.receive<DelegationResult>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest,
                ApiError("bad_request", "parse_error", "Invalid JSON"))
            return@post
        }

        if (result.status !in listOf("completed", "failed")) {
            call.respond(HttpStatusCode.BadRequest,
                ApiError("validation_error", "invalid_status", "status must be 'completed' or 'failed'"))
            return@post
        }

        val row = query {
            Delegations.select { Delegations.id eq result.delegation_id }.singleOrNull()
        }

        if (row == null) {
            call.respond(HttpStatusCode.NotFound,
                ApiError("not_found", "not_found", "Delegation not found"))
            return@post
        }

        if (row[Delegations.status] in listOf("completed", "failed", "timed_out")) {
            call.respond(HttpStatusCode.Conflict,
                ApiError("conflict", "already_finalized", "Delegation already in terminal state"))
            return@post
        }

        val now        = Instant.now()
        val startedAt  = row[Delegations.startedAt] ?: row[Delegations.createdAt]
        val durationMs = (now.toEpochMilli() - startedAt.toEpochMilli()).toInt()

        // v0.3: fallback escalation — if failed and fallback agent exists, retry there
        if (result.status == "failed" && row[Delegations.fallbackAgentId] != null && !row[Delegations.fallbackTriggered]) {
            val fallbackAgentId = row[Delegations.fallbackAgentId]!!

            val fallbackWebhookUrl = query {
                Agents.slice(Agents.webhookUrl)
                    .select { Agents.id eq fallbackAgentId }
                    .singleOrNull()?.get(Agents.webhookUrl)
            }

            if (fallbackWebhookUrl != null) {
                val fallbackDelegationId = newId("dl")
                val fallbackNow = Instant.now()

                query {
                    // Mark original as fallback-triggered (not failed yet)
                    Delegations.update({ Delegations.id eq result.delegation_id }) {
                        it[Delegations.fallbackTriggered] = true
                        it[Delegations.updatedAt] = fallbackNow
                    }

                    // Find active match between requester and fallback
                    val fromAgentId = row[Delegations.fromAgentId]
                    val fallbackMatch = Matches.select {
                        (Matches.status eq "active") and (
                            ((Matches.agentAId eq fromAgentId) and (Matches.agentBId eq fallbackAgentId)) or
                            ((Matches.agentAId eq fallbackAgentId) and (Matches.agentBId eq fromAgentId))
                        )
                    }.singleOrNull()

                    if (fallbackMatch != null) {
                        Delegations.insert {
                            it[id]                 = fallbackDelegationId
                            it[matchId]            = fallbackMatch[Matches.id]
                            it[fromAgentId]        = fromAgentId
                            it[toAgentId]          = fallbackAgentId
                            it[task]               = row[Delegations.task]
                            it[input]              = row[Delegations.input]
                            it[callbackUrl]        = row[Delegations.callbackUrl]
                            it[idempotencyKey]     = "${row[Delegations.idempotencyKey]}_fallback"
                            it[timeoutSeconds]     = row[Delegations.timeoutSeconds]
                            it[metadata]           = row[Delegations.metadata]
                            it[status]             = "queued"
                            it[chainId]            = row[Delegations.chainId]
                            it[chainDepth]         = row[Delegations.chainDepth]
                            it[parentDelegationId] = result.delegation_id
                            it[createdAt]          = fallbackNow
                        }
                    }
                }

                // Dispatch to fallback
                scope.launch {
                    query {
                        Delegations.update({ Delegations.id eq fallbackDelegationId }) {
                            it[Delegations.status]    = "running"
                            it[Delegations.startedAt] = Instant.now()
                        }
                    }
                    val fallbackDelegation = query {
                        Delegations.select { Delegations.id eq fallbackDelegationId }
                            .singleOrNull()?.toDelegation()
                    } ?: return@launch

                    WebhookService.deliver(
                        delegation    = fallbackDelegation,
                        event         = "delegation.dispatched",
                        webhookSecret = webhookSecret,
                        output        = mapOf(
                            "delegation_id"   to fallbackDelegationId,
                            "task"            to fallbackDelegation.task,
                            "input"           to fallbackDelegation.input,
                            "callback_url"    to "${System.getenv("PUBLIC_URL") ?: "http://localhost:8080"}/webhooks/result",
                            "fallback_for"    to result.delegation_id
                        )
                    )
                }

                call.respond(HttpStatusCode.OK,
                    mapOf("delegation_id" to result.delegation_id, "status" to "escalated_to_fallback",
                          "fallback_delegation_id" to fallbackDelegationId))
                return@post
            }
        }

        // Normal completion — persist result
        query {
            Delegations.update({ Delegations.id eq result.delegation_id }) {
                it[Delegations.status]      = result.status
                it[Delegations.output]      = result.output?.toJsonString()
                it[Delegations.error]       = result.error?.toJsonString()
                it[Delegations.durationMs]  = durationMs
                it[Delegations.completedAt] = now
            }

            // Update executor stats
            val executorId = row[Delegations.toAgentId]
            val statField  = if (result.status == "completed") "success_count" else "fail_count"
            Agents.update({ Agents.id eq executorId }) {
                it[Agents.stats] = "jsonb_set(stats::jsonb, '{$statField}', ((stats::jsonb->>'$statField')::int + 1)::text::jsonb)"
                it[Agents.updatedAt] = now
            }
        }

        // v0.3: complete billing transaction if payment is pending
        if (result.status == "completed") {
            scope.launch {
                query {
                    BillingTransactions.update({
                        (BillingTransactions.delegationId eq result.delegation_id) and
                        (BillingTransactions.status eq "pending")
                    }) { it[BillingTransactions.status] = "completed" }
                }
            }
        }

        val fullDelegation = query {
            Delegations.select { Delegations.id eq result.delegation_id }.single().toDelegation()
        }

        val event = if (result.status == "completed") "delegation.completed" else "delegation.failed"
        scope.launch {
            WebhookService.deliver(
                delegation    = fullDelegation,
                event         = event,
                webhookSecret = webhookSecret,
                output        = result.output,
                error         = result.error
            )
        }

        call.respond(HttpStatusCode.OK, mapOf(
            "delegation_id" to result.delegation_id,
            "status"        to result.status,
            "duration_ms"   to durationMs
        ))
    }
}

// Row mapper (duplicated here to avoid circular imports)
private fun ResultRow.toDelegation() = Delegation(
    id                 = this[Delegations.id],
    matchId            = this[Delegations.matchId],
    fromAgentId        = this[Delegations.fromAgentId],
    toAgentId          = this[Delegations.toAgentId],
    task               = this[Delegations.task],
    input              = this[Delegations.input].fromJson(),
    callbackUrl        = this[Delegations.callbackUrl],
    idempotencyKey     = this[Delegations.idempotencyKey],
    timeoutSeconds     = this[Delegations.timeoutSeconds],
    metadata           = this[Delegations.metadata].fromJson(),
    status             = this[Delegations.status],
    output             = this[Delegations.output]?.fromJson(),
    error              = this[Delegations.error]?.fromJson(),
    durationMs         = this[Delegations.durationMs],
    chainId            = this[Delegations.chainId],
    chainDepth         = this[Delegations.chainDepth],
    parentDelegationId = this[Delegations.parentDelegationId],
    fallbackAgentId    = this[Delegations.fallbackAgentId],
    fallbackTriggered  = this[Delegations.fallbackTriggered],
    createdAt          = this[Delegations.createdAt].toString(),
    startedAt          = this[Delegations.startedAt]?.toString(),
    completedAt        = this[Delegations.completedAt]?.toString()
)

private fun ResultRow.updatedAt() = this[Delegations.completedAt]

// Extension to avoid import issue
private val Delegations.updatedAt get() = completedAt
