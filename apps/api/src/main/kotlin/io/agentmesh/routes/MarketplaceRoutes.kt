package io.agentmesh.routes

import io.agentmesh.db.Agents
import io.agentmesh.db.DatabaseFactory.query
import io.agentmesh.db.MarketplaceReviews
import io.agentmesh.models.*
import io.agentmesh.util.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import java.time.Instant

fun Route.marketplaceRoutes() {

    // ─────────────────────────────────────────
    // GET /marketplace — list marketplace agents
    // ─────────────────────────────────────────
    get("/marketplace") {
        val category = call.request.queryParameters["category"]
        val has = call.request.queryParameters["has"]?.split(",")
        val maxPrice = call.request.queryParameters["max_price_cents"]?.toIntOrNull()
        val minRating = call.request.queryParameters["min_rating"]?.toIntOrNull()
        val sort = call.request.queryParameters["sort"] ?: "delegations"
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)

        val agents = query {
            var q = Agents.select {
                (Agents.public eq true) and (Agents.marketplaceListed eq true)
            }

            q.orderBy(Agents.createdAt, SortOrder.DESC)
                .limit(limit)
                .toList()
                .map { row ->
                    val stats = row[Agents.stats].fromJson<AgentStats>()
                    val agentId = row[Agents.id]

                    // Get average rating
                    val reviews = MarketplaceReviews.select { MarketplaceReviews.agentId eq agentId }.toList()
                    val avgRating = if (reviews.isNotEmpty()) {
                        reviews.map { it[MarketplaceReviews.rating] }.average()
                    } else null

                    mapOf(
                        "id" to row[Agents.id],
                        "name" to row[Agents.name],
                        "description" to row[Agents.description],
                        "framework" to row[Agents.framework],
                        "has" to row[Agents.has].toList(),
                        "needs" to row[Agents.needs].toList(),
                        "status" to row[Agents.status],
                        "trust_tier" to row[Agents.trustTier],
                        "tagline" to row[Agents.marketplaceTagline],
                        "categories" to row[Agents.marketplaceCategories].toList(),
                        "avg_rating" to avgRating,
                        "review_count" to reviews.size,
                        "delegations_sent" to stats.delegationsSent,
                        "delegations_received" to stats.delegationsReceived
                    )
                }
                .let { list ->
                    var filtered = list
                    if (category != null) filtered = filtered.filter {
                        (it["categories"] as List<*>).contains(category)
                    }
                    if (has != null) filtered = filtered.filter { agent ->
                        (agent["has"] as List<*>).any { it in has }
                    }
                    if (minRating != null) filtered = filtered.filter {
                        (it["avg_rating"] as? Double ?: 0.0) >= minRating
                    }
                    filtered
                }
                .let { list ->
                    when (sort) {
                        "rating" -> list.sortedByDescending { it["avg_rating"] as? Double ?: 0.0 }
                        "price" -> list  // price sort requires billing rates — sort by delegation count as fallback
                        else -> list.sortedByDescending { it["delegations_received"] as Int }
                    }
                }
        }

        call.respond(mapOf("agents" to agents, "count" to agents.size))
    }

    // ─────────────────────────────────────────
    // GET /marketplace/{agentId} — full listing
    // ─────────────────────────────────────────
    get("/marketplace/{agentId}") {
        val agentId = call.parameters["agentId"]!!
        val agent = query {
            Agents.select {
                (Agents.id eq agentId) and (Agents.marketplaceListed eq true)
            }.singleOrNull()
        }

        if (agent == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", "not_found", "Agent not listed on marketplace"))
            return@get
        }

        val reviews = query {
            MarketplaceReviews.select { MarketplaceReviews.agentId eq agentId }
                .orderBy(MarketplaceReviews.createdAt, SortOrder.DESC)
                .limit(50)
                .map { row ->
                    mapOf(
                        "id" to row[MarketplaceReviews.id],
                        "reviewer_agent_id" to row[MarketplaceReviews.reviewerAgentId],
                        "rating" to row[MarketplaceReviews.rating],
                        "comment" to row[MarketplaceReviews.comment],
                        "created_at" to row[MarketplaceReviews.createdAt].toString()
                    )
                }
        }

        val stats = agent[Agents.stats].fromJson<AgentStats>()
        val avgRating = if (reviews.isNotEmpty()) reviews.mapNotNull { it["rating"] as? Int }.average() else null

        call.respond(mapOf(
            "id" to agent[Agents.id],
            "name" to agent[Agents.name],
            "description" to agent[Agents.description],
            "framework" to agent[Agents.framework],
            "has" to agent[Agents.has].toList(),
            "needs" to agent[Agents.needs].toList(),
            "tagline" to agent[Agents.marketplaceTagline],
            "categories" to agent[Agents.marketplaceCategories].toList(),
            "avg_rating" to avgRating,
            "reviews" to reviews,
            "stats" to stats
        ))
    }

    // ─────────────────────────────────────────
    // POST /marketplace/list — list your agent
    // ─────────────────────────────────────────
    post("/marketplace/list") {
        val agentId = call.authenticatedAgentId()
            ?: return@post call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))

        val req = runCatching { call.receive<ListMarketplaceRequest>() }.getOrDefault(ListMarketplaceRequest())

        query {
            Agents.update({ Agents.id eq agentId }) {
                it[marketplaceListed] = true
                if (req.tagline != null) it[marketplaceTagline] = req.tagline
                if (req.categories.isNotEmpty()) it[marketplaceCategories] = req.categories.toTypedArray()
                it[updatedAt] = Instant.now()
            }
        }

        call.respond(mapOf("agent_id" to agentId, "marketplace_listed" to true))
    }

    // ─────────────────────────────────────────
    // POST /marketplace/reviews — submit a review
    // ─────────────────────────────────────────
    post("/marketplace/reviews") {
        val reviewerAgentId = call.authenticatedAgentId()
            ?: return@post call.respond(HttpStatusCode.Unauthorized,
                ApiError("unauthorized", "unauthorized", "Invalid API key"))

        val req = call.receive<SubmitReviewRequest>()

        if (req.rating !in 1..5) {
            call.respond(HttpStatusCode.BadRequest,
                ApiError("validation_error", "invalid_rating", "Rating must be 1-5"))
            return@post
        }

        val reviewId = newId("mr")
        query {
            MarketplaceReviews.insert {
                it[id] = reviewId
                it[agentId] = req.agentId
                it[MarketplaceReviews.reviewerAgentId] = reviewerAgentId
                it[rating] = req.rating
                it[comment] = req.comment
                it[delegationId] = req.delegationId
                it[createdAt] = Instant.now()
            }
        }

        call.respond(HttpStatusCode.Created, mapOf("review_id" to reviewId))
    }

    // ─────────────────────────────────────────
    // GET /marketplace/{agentId}/reviews
    // ─────────────────────────────────────────
    get("/marketplace/{agentId}/reviews") {
        val agentId = call.parameters["agentId"]!!
        val reviews = query {
            MarketplaceReviews.select { MarketplaceReviews.agentId eq agentId }
                .orderBy(MarketplaceReviews.createdAt, SortOrder.DESC)
                .limit(100)
                .map { row ->
                    mapOf(
                        "id" to row[MarketplaceReviews.id],
                        "reviewer_agent_id" to row[MarketplaceReviews.reviewerAgentId],
                        "rating" to row[MarketplaceReviews.rating],
                        "comment" to row[MarketplaceReviews.comment],
                        "created_at" to row[MarketplaceReviews.createdAt].toString()
                    )
                }
        }
        call.respond(mapOf("reviews" to reviews, "count" to reviews.size))
    }
}
