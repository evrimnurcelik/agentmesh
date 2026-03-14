package io.agentmesh.routes

import io.agentmesh.db.Agents
import io.agentmesh.db.BillingRates
import io.agentmesh.db.BillingTransactions
import io.agentmesh.db.Capabilities
import io.agentmesh.db.DatabaseFactory.query
import io.agentmesh.db.Delegations
import io.agentmesh.db.Matches
import io.agentmesh.db.DelegationEvents
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
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant

fun Route.delegationRoutes(webhookSecret: String) {

    val scope = CoroutineScope(Dispatchers.IO)

    // ─────────────────────────────────────────
    // POST /delegate
    // ─────────────────────────────────────────
    post("/delegate") {
        val agentId = call.authenticatedAgentId()
            ?: return@post call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))

        val req = runCatching { call.receive<DelegateRequest>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest,
                ApiError("bad_request", "parse_error", "Invalid JSON body"))
            return@post
        }

        // v0.2: chain depth guard (max 5 hops)
        if (req.chainDepth > 5) {
            call.respond(HttpStatusCode.BadRequest,
                ApiError("chain_too_deep", "chain_too_deep", "Delegation chains are limited to 5 hops"))
            return@post
        }

        val target = query { Agents.select { Agents.id eq req.to }.singleOrNull() }
        if (target == null) {
            call.respond(HttpStatusCode.NotFound,
                ApiError("agent_not_found", "not_found", "Target agent not found"))
            return@post
        }

        val match = query {
            Matches.select {
                (Matches.status eq "active") and (
                    ((Matches.agentAId eq agentId) and (Matches.agentBId eq req.to)) or
                    ((Matches.agentAId eq req.to)  and (Matches.agentBId eq agentId))
                )
            }.singleOrNull()
        }
        if (match == null) {
            call.respond(HttpStatusCode.Forbidden,
                ApiError("no_active_match", "forbidden", "No active match. Both owners must approve first."))
            return@post
        }

        val contract = match[Matches.contract]?.fromJson<MatchContract>()
        if (contract != null && req.task !in contract.allowedTaskTypes) {
            call.respond(HttpStatusCode.Forbidden,
                ApiError("task_not_allowed", "forbidden",
                    "Task '${req.task}' not in contract. Allowed: ${contract.allowedTaskTypes.joinToString(", ")}"))
            return@post
        }

        // v0.4: Schema validation
        val capability = query {
            Capabilities.selectAll()
                .firstOrNull { it[Capabilities.taskTypes].contains(req.task) }
        }

        if (capability != null) {
            val schemaStr = capability[Capabilities.inputSchema]
            if (schemaStr.isNotBlank() && schemaStr != "{}" && schemaStr != "null") {
                try {
                    val schema = schemaStr.fromJson<Map<String, Map<String, Any?>>>()
                    val validationErrors = mutableListOf<String>()

                    schema.forEach { (field, rules) ->
                        val required = rules["required"] as? Boolean ?: false
                        val expectedType = rules["type"] as? String
                        val value = req.input[field]

                        if (required && value == null) {
                            validationErrors.add("Missing required field: '$field'")
                        }
                        if (value != null && expectedType != null) {
                            val typeOk = when (expectedType) {
                                "string"  -> value is String
                                "number"  -> value is Number
                                "integer" -> value is Int || value is Long
                                "boolean" -> value is Boolean
                                "array"   -> value is List<*>
                                "object"  -> value is Map<*, *>
                                else -> true
                            }
                            if (!typeOk) validationErrors.add("Field '$field' must be $expectedType")
                        }
                    }

                    if (validationErrors.isNotEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, ApiError(
                            "schema_validation_failed", "invalid_input",
                            validationErrors.joinToString("; ")
                        ))
                        return@post
                    }
                } catch (_: Exception) {
                    // Schema parse failed — skip validation
                }
            }
        }

        // Idempotency check
        val existing = query {
            Delegations.select {
                (Delegations.fromAgentId eq agentId) and
                (Delegations.idempotencyKey eq req.idempotencyKey)
            }.singleOrNull()
        }
        if (existing != null) {
            call.respond(HttpStatusCode.Conflict,
                ApiError("idempotency_conflict", "conflict",
                    "Delegation with this idempotency_key already exists: ${existing[Delegations.id]}"))
            return@post
        }

        // v0.2: resolve or create chainId
        val chainId = req.chainId ?: newId("ch")

        // v0.3: validate fallback agent has active match too
        if (req.fallbackAgentId != null) {
            val fallbackMatch = query {
                Matches.select {
                    (Matches.status eq "active") and (
                        ((Matches.agentAId eq agentId) and (Matches.agentBId eq req.fallbackAgentId)) or
                        ((Matches.agentAId eq req.fallbackAgentId) and (Matches.agentBId eq agentId))
                    )
                }.singleOrNull()
            }
            if (fallbackMatch == null) {
                call.respond(HttpStatusCode.Forbidden,
                    ApiError("no_fallback_match", "forbidden", "No active match with fallback agent"))
                return@post
            }
        }

        val delegationId = newId("dl")
        val now          = Instant.now()

        query {
            Delegations.insert {
                it[id]                 = delegationId
                it[matchId]            = match[Matches.id]
                it[fromAgentId]        = agentId
                it[toAgentId]          = req.to
                it[task]               = req.task
                it[input]              = req.input.toJsonString()
                it[callbackUrl]        = req.callbackUrl
                it[idempotencyKey]     = req.idempotencyKey
                it[timeoutSeconds]     = req.timeoutSeconds.coerceIn(1, 300)
                it[metadata]           = req.metadata.toJsonString()
                it[status]             = "queued"
                it[Delegations.chainId]            = chainId
                it[chainDepth]         = req.chainDepth
                it[parentDelegationId] = req.parentDelegationId
                it[fallbackAgentId]    = req.fallbackAgentId
                it[streaming]          = req.streaming
                it[createdAt]          = now
            }
        }

        // v0.3: create pending billing transaction if payee has a rate for this task
        scope.launch {
            maybeCreateBillingTransaction(delegationId, agentId, req.to, req.task)
        }

        scope.launch {
            dispatchToExecutor(delegationId, target[Agents.webhookUrl], webhookSecret)
        }

        call.respond(HttpStatusCode.Accepted, DelegateResponse(
            delegationId = delegationId,
            chainId      = chainId,
            status       = "queued"
        ))
    }

    // ─────────────────────────────────────────
    // POST /delegations/{delegationId}/replay — v0.4
    // ─────────────────────────────────────────
    post("/delegations/{delegationId}/replay") {
        val delegationId = call.parameters["delegationId"]!!
        val agentId = call.authenticatedAgentId()
            ?: return@post call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))

        val original = query {
            Delegations.select { Delegations.id eq delegationId }.singleOrNull()
        }

        if (original == null) {
            call.respond(HttpStatusCode.NotFound,
                ApiError("not_found", "not_found", "Delegation not found"))
            return@post
        }

        if (original[Delegations.fromAgentId] != agentId) {
            call.respond(HttpStatusCode.Forbidden,
                ApiError("forbidden", "forbidden", "Only the sender can replay a delegation"))
            return@post
        }

        val status = original[Delegations.status]
        if (status != "failed" && status != "timed_out") {
            call.respond(HttpStatusCode.BadRequest,
                ApiError("invalid_status", "bad_request", "Only failed or timed_out delegations can be replayed"))
            return@post
        }

        val target = query {
            Agents.select { Agents.id eq original[Delegations.toAgentId] }.singleOrNull()
        }
        if (target == null) {
            call.respond(HttpStatusCode.NotFound,
                ApiError("agent_not_found", "not_found", "Target agent no longer exists"))
            return@post
        }

        val newDelegationId = newId("dl")
        val now = Instant.now()
        val newIdempotencyKey = "${original[Delegations.idempotencyKey]}_replay_${System.currentTimeMillis()}"

        query {
            Delegations.insert {
                it[id]                 = newDelegationId
                it[matchId]            = original[Delegations.matchId]
                it[fromAgentId]        = original[Delegations.fromAgentId]
                it[toAgentId]          = original[Delegations.toAgentId]
                it[task]               = original[Delegations.task]
                it[input]              = original[Delegations.input]
                it[callbackUrl]        = original[Delegations.callbackUrl]
                it[idempotencyKey]     = newIdempotencyKey
                it[timeoutSeconds]     = original[Delegations.timeoutSeconds]
                it[metadata]           = original[Delegations.metadata]
                it[Delegations.status] = "queued"
                it[chainId]            = original[Delegations.chainId]
                it[chainDepth]         = original[Delegations.chainDepth]
                it[parentDelegationId] = delegationId
                it[fallbackAgentId]    = original[Delegations.fallbackAgentId]
                it[createdAt]          = now
            }
        }

        scope.launch {
            dispatchToExecutor(newDelegationId, target[Agents.webhookUrl], webhookSecret)
        }

        call.respond(HttpStatusCode.Accepted, DelegateResponse(
            delegationId = newDelegationId,
            chainId      = original[Delegations.chainId],
            status       = "queued"
        ))
    }

    // ─────────────────────────────────────────
    // GET /delegations
    // ─────────────────────────────────────────
    get("/delegations") {
        val agentId   = call.authenticatedAgentId()
            ?: return@get call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))
        val direction = call.request.queryParameters["direction"] ?: "all"
        val status    = call.request.queryParameters["status"]
        val limit     = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)

        val delegations = query {
            var q = when (direction) {
                "sent"     -> Delegations.select { Delegations.fromAgentId eq agentId }
                "received" -> Delegations.select { Delegations.toAgentId   eq agentId }
                else       -> Delegations.select {
                    (Delegations.fromAgentId eq agentId) or (Delegations.toAgentId eq agentId)
                }
            }
            if (status != null) q = q.andWhere { Delegations.status eq status }
            q.orderBy(Delegations.createdAt, SortOrder.DESC).limit(limit).map { it.toDelegation() }
        }

        call.respond(mapOf("delegations" to delegations, "count" to delegations.size))
    }

    // ─────────────────────────────────────────
    // GET /delegations/{delegationId}
    // ─────────────────────────────────────────
    get("/delegations/{delegationId}") {
        val delegationId = call.parameters["delegationId"]!!
        val agentId      = call.authenticatedAgentId()
            ?: return@get call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))

        val delegation = query {
            Delegations.select { Delegations.id eq delegationId }.singleOrNull()?.toDelegation()
        }

        if (delegation == null) {
            call.respond(HttpStatusCode.NotFound,
                ApiError("not_found", "not_found", "Delegation not found"))
            return@get
        }
        if (delegation.fromAgentId != agentId && delegation.toAgentId != agentId) {
            call.respond(HttpStatusCode.Forbidden,
                ApiError("forbidden", "forbidden", "Access denied"))
            return@get
        }
        call.respond(delegation)
    }
    // ─────────────────────────────────────────
    // GET /delegations/{delegationId}/events — poll for streaming events (v0.4)
    // ─────────────────────────────────────────
    get("/delegations/{delegationId}/events") {
        val delegationId = call.parameters["delegationId"]!!
        val sinceSequence = call.request.queryParameters["since_sequence"]?.toIntOrNull() ?: 0

        val events = query {
            DelegationEvents.select {
                (DelegationEvents.delegationId eq delegationId) and
                (DelegationEvents.sequence greater sinceSequence)
            }.orderBy(DelegationEvents.sequence, SortOrder.ASC)
                .map { row ->
                    mapOf(
                        "id" to row[DelegationEvents.id],
                        "delegation_id" to row[DelegationEvents.delegationId],
                        "sequence" to row[DelegationEvents.sequence],
                        "event_type" to row[DelegationEvents.eventType],
                        "data" to row[DelegationEvents.data].fromJson<Map<String, Any?>>(),
                        "created_at" to row[DelegationEvents.createdAt].toString()
                    )
                }
        }

        call.respond(mapOf("events" to events, "count" to events.size))
    }

    // ─────────────────────────────────────────
    // POST /webhooks/progress — receive streaming progress events (v0.4)
    // ─────────────────────────────────────────
    post("/webhooks/progress") {
        val req = runCatching { call.receive<ProgressEvent>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest,
                ApiError("bad_request", "parse_error", "Invalid JSON"))
            return@post
        }

        if (req.eventType !in listOf("progress", "partial_output", "error", "completed")) {
            call.respond(HttpStatusCode.BadRequest,
                ApiError("validation_error", "invalid_event_type", "event_type must be progress|partial_output|error|completed"))
            return@post
        }

        val delegation = query {
            Delegations.select { Delegations.id eq req.delegationId }.singleOrNull()
        }
        if (delegation == null) {
            call.respond(HttpStatusCode.NotFound,
                ApiError("not_found", "not_found", "Delegation not found"))
            return@post
        }

        val eventId = newId("de")
        query {
            DelegationEvents.insert {
                it[id] = eventId
                it[delegationId] = req.delegationId
                it[sequence] = req.sequence
                it[eventType] = req.eventType
                it[data] = req.data.toJsonString()
                it[createdAt] = Instant.now()
            }
        }

        call.respond(HttpStatusCode.OK, mapOf("event_id" to eventId, "delegation_id" to req.delegationId))
    }
}

// ─────────────────────────────────────────
// Dispatch task to executor agent
// ─────────────────────────────────────────
private suspend fun dispatchToExecutor(delegationId: String, executorWebhookUrl: String, webhookSecret: String) {
    query {
        Delegations.update({ Delegations.id eq delegationId }) {
            it[Delegations.status]    = "running"
            it[Delegations.startedAt] = Instant.now()
        }
    }

    val delegation = query {
        Delegations.select { Delegations.id eq delegationId }.singleOrNull()?.toDelegation()
    } ?: return

    val payload = mapOf(
        "delegation_id"   to delegation.id,
        "task"            to delegation.task,
        "input"           to delegation.input,
        "callback_url"    to "${System.getenv("PUBLIC_URL") ?: "http://localhost:8080"}/webhooks/result",
        "timeout_seconds" to delegation.timeoutSeconds,
        "chain_id"        to delegation.chainId,
        "chain_depth"     to delegation.chainDepth
    )

    WebhookService.deliver(
        delegation    = delegation,
        event         = "delegation.dispatched",
        webhookSecret = webhookSecret,
        output        = payload
    )
}

// ─────────────────────────────────────────
// v0.3: Create billing transaction if executor has a rate
// ─────────────────────────────────────────
private suspend fun maybeCreateBillingTransaction(
    delegationId: String,
    payerAgentId: String,
    payeeAgentId: String,
    taskType: String
) {
    val rate = query {
        BillingRates.select {
            (BillingRates.agentId eq payeeAgentId) and
            (BillingRates.taskType eq taskType) and
            (BillingRates.active eq true)
        }.singleOrNull()
    } ?: return   // no rate set = free

    if (rate[BillingRates.priceCents] == 0) return

    query {
        BillingTransactions.insert {
            it[id]              = newId("bt")
            it[BillingTransactions.delegationId]  = delegationId
            it[BillingTransactions.payerAgentId]  = payerAgentId
            it[BillingTransactions.payeeAgentId]  = payeeAgentId
            it[amountCents]     = rate[BillingRates.priceCents]
            it[currency]        = rate[BillingRates.currency]
            it[status]          = "pending"
            it[createdAt]       = Instant.now()
        }
    }
}

// ─── Row mapper ───────────────────────────────────────────────
private fun ResultRow.toDelegation() = Delegation(
    id                  = this[Delegations.id],
    matchId             = this[Delegations.matchId],
    fromAgentId         = this[Delegations.fromAgentId],
    toAgentId           = this[Delegations.toAgentId],
    task                = this[Delegations.task],
    input               = this[Delegations.input].fromJson(),
    callbackUrl         = this[Delegations.callbackUrl],
    idempotencyKey      = this[Delegations.idempotencyKey],
    timeoutSeconds      = this[Delegations.timeoutSeconds],
    metadata            = this[Delegations.metadata].fromJson(),
    status              = this[Delegations.status],
    output              = this[Delegations.output]?.fromJson(),
    error               = this[Delegations.error]?.fromJson(),
    durationMs          = this[Delegations.durationMs],
    chainId             = this[Delegations.chainId],
    chainDepth          = this[Delegations.chainDepth],
    parentDelegationId  = this[Delegations.parentDelegationId],
    fallbackAgentId     = this[Delegations.fallbackAgentId],
    fallbackTriggered   = this[Delegations.fallbackTriggered],
    createdAt           = this[Delegations.createdAt].toString(),
    startedAt           = this[Delegations.startedAt]?.toString(),
    completedAt         = this[Delegations.completedAt]?.toString()
)
