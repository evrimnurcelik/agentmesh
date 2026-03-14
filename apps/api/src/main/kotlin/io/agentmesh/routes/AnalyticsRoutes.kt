package io.agentmesh.routes

import io.agentmesh.db.BillingTransactions
import io.agentmesh.db.DatabaseFactory.query
import io.agentmesh.db.Delegations
import io.agentmesh.models.ApiError
import io.agentmesh.util.authenticatedAgentId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

fun Route.analyticsRoutes() {

    // ─────────────────────────────────────────
    // GET /analytics/delegations — volume by day
    // ─────────────────────────────────────────
    get("/analytics/delegations") {
        val agentId = call.authenticatedAgentId()
            ?: return@get call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))

        val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 7).coerceIn(1, 90)
        val since = Instant.now().minusSeconds(days * 24 * 3600L)

        val delegations = query {
            Delegations.select {
                ((Delegations.fromAgentId eq agentId) or (Delegations.toAgentId eq agentId)) and
                (Delegations.createdAt.greaterEq(since))
            }.orderBy(Delegations.createdAt, SortOrder.ASC).toList()
        }

        val byDate = delegations.groupBy {
            LocalDate.ofInstant(it[Delegations.createdAt], ZoneOffset.UTC).toString()
        }

        val series = byDate.map { (date, items) ->
            mapOf(
                "date" to date,
                "total" to items.size,
                "completed" to items.count { it[Delegations.status] == "completed" },
                "failed" to items.count { it[Delegations.status] in listOf("failed", "timed_out") },
                "running" to items.count { it[Delegations.status] == "running" }
            )
        }

        val total = delegations.size
        val completed = delegations.count { it[Delegations.status] == "completed" }
        val latencies = delegations.mapNotNull { it[Delegations.durationMs] }
        val avgLatency = if (latencies.isNotEmpty()) latencies.average().toInt() else 0

        call.respond(mapOf(
            "series" to series,
            "summary" to mapOf(
                "total" to total,
                "success_rate" to if (total > 0) Math.round(completed.toDouble() / total * 100) / 100.0 else 0,
                "avg_latency_ms" to avgLatency
            )
        ))
    }

    // ─────────────────────────────────────────
    // GET /analytics/latency — by task type
    // ─────────────────────────────────────────
    get("/analytics/latency") {
        val agentId = call.authenticatedAgentId()
            ?: return@get call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))

        val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 7).coerceIn(1, 90)
        val since = Instant.now().minusSeconds(days * 24 * 3600L)

        val delegations = query {
            Delegations.select {
                ((Delegations.fromAgentId eq agentId) or (Delegations.toAgentId eq agentId)) and
                (Delegations.createdAt.greaterEq(since)) and
                (Delegations.durationMs.isNotNull())
            }.toList()
        }

        val byTask = delegations.groupBy { it[Delegations.task] }
        val latencyByTask = byTask.map { (task, items) ->
            val durations = items.mapNotNull { it[Delegations.durationMs] }.sorted()
            mapOf(
                "task" to task,
                "count" to items.size,
                "avg_ms" to if (durations.isNotEmpty()) durations.average().toInt() else 0,
                "p50_ms" to durations.getOrNull(durations.size / 2),
                "p95_ms" to durations.getOrNull((durations.size * 0.95).toInt()),
                "p99_ms" to durations.getOrNull((durations.size * 0.99).toInt())
            )
        }

        call.respond(mapOf("by_task" to latencyByTask))
    }

    // ─────────────────────────────────────────
    // GET /analytics/cost — spend/earn by day
    // ─────────────────────────────────────────
    get("/analytics/cost") {
        val agentId = call.authenticatedAgentId()
            ?: return@get call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))

        val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 7).coerceIn(1, 90)
        val since = Instant.now().minusSeconds(days * 24 * 3600L)

        val transactions = query {
            BillingTransactions.select {
                ((BillingTransactions.payerAgentId eq agentId) or (BillingTransactions.payeeAgentId eq agentId)) and
                (BillingTransactions.createdAt.greaterEq(since))
            }.toList()
        }

        val byDate = transactions.groupBy {
            LocalDate.ofInstant(it[BillingTransactions.createdAt], ZoneOffset.UTC).toString()
        }

        val series = byDate.map { (date, items) ->
            val earned = items.filter { it[BillingTransactions.payeeAgentId] == agentId }
                .sumOf { it[BillingTransactions.amountCents] }
            val spent = items.filter { it[BillingTransactions.payerAgentId] == agentId }
                .sumOf { it[BillingTransactions.amountCents] }
            mapOf("date" to date, "earned_cents" to earned, "spent_cents" to spent)
        }

        call.respond(mapOf("series" to series))
    }

    // ─────────────────────────────────────────
    // GET /analytics/reliability — success rate trend
    // ─────────────────────────────────────────
    get("/analytics/reliability") {
        val agentId = call.authenticatedAgentId()
            ?: return@get call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))

        val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 30).coerceIn(1, 90)
        val since = Instant.now().minusSeconds(days * 24 * 3600L)

        val delegations = query {
            Delegations.select {
                (Delegations.toAgentId eq agentId) and
                (Delegations.createdAt.greaterEq(since)) and
                (Delegations.status inList listOf("completed", "failed", "timed_out"))
            }.toList()
        }

        val byDate = delegations.groupBy {
            LocalDate.ofInstant(it[Delegations.createdAt], ZoneOffset.UTC).toString()
        }

        val series = byDate.map { (date, items) ->
            val total = items.size
            val success = items.count { it[Delegations.status] == "completed" }
            mapOf(
                "date" to date,
                "total" to total,
                "success" to success,
                "rate" to if (total > 0) Math.round(success.toDouble() / total * 10000) / 100.0 else 0
            )
        }

        call.respond(mapOf("series" to series))
    }

    // ─────────────────────────────────────────
    // GET /analytics/top-pairs — most active agent pairs
    // ─────────────────────────────────────────
    get("/analytics/top-pairs") {
        val agentId = call.authenticatedAgentId()
            ?: return@get call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))

        val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 30).coerceIn(1, 90)
        val since = Instant.now().minusSeconds(days * 24 * 3600L)

        val delegations = query {
            Delegations.select {
                ((Delegations.fromAgentId eq agentId) or (Delegations.toAgentId eq agentId)) and
                (Delegations.createdAt.greaterEq(since))
            }.toList()
        }

        val pairs = delegations.groupBy {
            val from = it[Delegations.fromAgentId]
            val to = it[Delegations.toAgentId]
            "$from → $to"
        }.map { (pair, items) ->
            mapOf(
                "pair" to pair,
                "count" to items.size,
                "completed" to items.count { it[Delegations.status] == "completed" },
                "avg_duration_ms" to (items.mapNotNull { it[Delegations.durationMs] }.let {
                    if (it.isNotEmpty()) it.average().toInt() else 0
                })
            )
        }.sortedByDescending { it["count"] as Int }
            .take(20)

        call.respond(mapOf("pairs" to pairs))
    }
}
