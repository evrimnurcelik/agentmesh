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
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant

@Serializable
data class StripeWebhookEvent(
    val type: String,
    val data: Map<String, @Serializable Any?>
)

fun Route.billingRoutes(stripeWebhookSecret: String) {

    // ─────────────────────────────────────────
    // GET /billing/rates — get rates for agent
    // ─────────────────────────────────────────
    get("/billing/rates") {
        val agentId = call.authenticatedAgentId()
            ?: return@get call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))

        val rates = query {
            BillingRates
                .select { (BillingRates.agentId eq agentId) and (BillingRates.active eq true) }
                .map { row ->
                    BillingRate(
                        agentId    = row[BillingRates.agentId],
                        taskType   = row[BillingRates.taskType],
                        priceCents = row[BillingRates.priceCents],
                        currency   = row[BillingRates.currency],
                        active     = row[BillingRates.active]
                    )
                }
        }

        call.respond(mapOf("rates" to rates, "agent_id" to agentId))
    }

    // ─────────────────────────────────────────
    // POST /billing/rates — set a rate for a task type
    // ─────────────────────────────────────────
    post("/billing/rates") {
        val agentId = call.authenticatedAgentId()
            ?: return@post call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))

        val req = runCatching { call.receive<SetBillingRateRequest>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest,
                ApiError("bad_request", "parse_error", "Invalid body"))
            return@post
        }

        if (req.priceCents < 0) {
            call.respond(HttpStatusCode.BadRequest,
                ApiError("validation_error", "invalid_price", "price_cents must be >= 0"))
            return@post
        }

        query {
            BillingRates.upsert(BillingRates.agentId, BillingRates.taskType) {
                it[BillingRates.agentId]    = agentId
                it[taskType]   = req.taskType
                it[priceCents] = req.priceCents
                it[currency]   = req.currency
                it[active]     = true
                it[createdAt]  = Instant.now()
            }
        }

        call.respond(HttpStatusCode.Created,
            mapOf("agent_id" to agentId, "task_type" to req.taskType,
                  "price_cents" to req.priceCents, "currency" to req.currency))
    }

    // ─────────────────────────────────────────
    // DELETE /billing/rates/{taskType} — deactivate a rate
    // ─────────────────────────────────────────
    delete("/billing/rates/{taskType}") {
        val agentId  = call.authenticatedAgentId()
            ?: return@delete call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))
        val taskType = call.parameters["taskType"]!!

        query {
            BillingRates.update({
                (BillingRates.agentId eq agentId) and (BillingRates.taskType eq taskType)
            }) { it[active] = false }
        }

        call.respond(HttpStatusCode.NoContent)
    }

    // ─────────────────────────────────────────
    // GET /billing/transactions — list transactions for agent
    // ─────────────────────────────────────────
    get("/billing/transactions") {
        val agentId = call.authenticatedAgentId()
            ?: return@get call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))

        val direction = call.request.queryParameters["direction"] ?: "all"
        val limit     = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)

        val txns = query {
            val q = when (direction) {
                "sent"     -> BillingTransactions.select { BillingTransactions.payerAgentId eq agentId }
                "received" -> BillingTransactions.select { BillingTransactions.payeeAgentId eq agentId }
                else       -> BillingTransactions.select {
                    (BillingTransactions.payerAgentId eq agentId) or
                    (BillingTransactions.payeeAgentId eq agentId)
                }
            }
            q.orderBy(BillingTransactions.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    BillingTransaction(
                        id                    = row[BillingTransactions.id],
                        delegationId          = row[BillingTransactions.delegationId],
                        payerAgentId          = row[BillingTransactions.payerAgentId],
                        payeeAgentId          = row[BillingTransactions.payeeAgentId],
                        amountCents           = row[BillingTransactions.amountCents],
                        currency              = row[BillingTransactions.currency],
                        stripePaymentIntentId = row[BillingTransactions.stripePaymentIntentId],
                        status                = row[BillingTransactions.status],
                        createdAt             = row[BillingTransactions.createdAt].toString()
                    )
                }
        }

        val totalEarned = txns.filter { it.payeeAgentId == agentId && it.status == "completed" }.sumOf { it.amountCents }
        val totalSpent  = txns.filter { it.payerAgentId == agentId && it.status == "completed" }.sumOf { it.amountCents }

        call.respond(mapOf(
            "transactions"    to txns,
            "count"           to txns.size,
            "total_earned_cents" to totalEarned,
            "total_spent_cents"  to totalSpent
        ))
    }

    // ─────────────────────────────────────────
    // POST /billing/stripe/webhook
    // Stripe sends payment_intent.succeeded / payment_intent.payment_failed here
    // ─────────────────────────────────────────
    post("/billing/stripe/webhook") {
        val body      = call.receiveText()
        val signature = call.request.header("Stripe-Signature") ?: ""

        // Verify Stripe signature
        val expectedSig = signWebhook(body, stripeWebhookSecret)
        if (!secureEquals(expectedSig, "sha256=${signature.substringAfter("v1=").substringBefore(",")}")) {
            call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "invalid_signature", "Invalid Stripe webhook signature"))
            return@post
        }

        val event = runCatching { body.fromJson<StripeWebhookEvent>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "parse_error", "Invalid event body"))
            return@post
        }

        when (event.type) {
            "payment_intent.succeeded" -> {
                val paymentIntentId = event.data["id"] as? String ?: return@post call.respond(HttpStatusCode.OK)
                query {
                    BillingTransactions.update({
                        BillingTransactions.stripePaymentIntentId eq paymentIntentId
                    }) { it[BillingTransactions.status] = "completed" }
                }
            }
            "payment_intent.payment_failed" -> {
                val paymentIntentId = event.data["id"] as? String ?: return@post call.respond(HttpStatusCode.OK)
                query {
                    BillingTransactions.update({
                        BillingTransactions.stripePaymentIntentId eq paymentIntentId
                    }) { it[BillingTransactions.status] = "failed" }
                }
            }
        }

        call.respond(HttpStatusCode.OK, mapOf("received" to true))
    }

    // ─────────────────────────────────────────
    // GET /billing/rates/agent/{agentId} — public rate card for any agent
    // ─────────────────────────────────────────
    get("/billing/rates/agent/{agentId}") {
        val agentId = call.parameters["agentId"]!!

        val rates = query {
            BillingRates
                .select { (BillingRates.agentId eq agentId) and (BillingRates.active eq true) }
                .map { row ->
                    mapOf(
                        "task_type"   to row[BillingRates.taskType],
                        "price_cents" to row[BillingRates.priceCents],
                        "currency"    to row[BillingRates.currency]
                    )
                }
        }

        call.respond(mapOf("agent_id" to agentId, "rates" to rates))
    }
}
