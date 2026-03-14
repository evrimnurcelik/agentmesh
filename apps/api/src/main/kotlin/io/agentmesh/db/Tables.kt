package io.agentmesh.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object Capabilities : Table("capabilities") {
    val id           = text("id")
    val label        = text("label")
    val category     = text("category")
    val taskTypes    = array<String>("task_types")
    val color        = text("color").nullable()
    val inputSchema  = jsonb("input_schema")
    val outputSchema = jsonb("output_schema")
    val createdAt    = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Agents : Table("agents") {
    val id              = text("id")
    val name            = text("name")
    val description     = text("description")
    val framework       = text("framework")
    val has             = array<String>("has")
    val needs           = array<String>("needs")
    val ownerEmail      = text("owner_email")
    val webhookUrl      = text("webhook_url")
    val apiKeyHash      = text("api_key_hash")
    val public          = bool("public").default(true)
    val trustTier       = text("trust_tier").default("public")
    val status          = text("status").default("offline")
    val lastPing        = timestamp("last_ping").nullable()
    val stats           = jsonb("stats")
    val verifiedAt      = timestamp("verified_at").nullable()
    val stripeAccountId = text("stripe_account_id").nullable()
    val createdAt       = timestamp("created_at")
    val updatedAt       = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object Matches : Table("matches") {
    val id             = text("id")
    val agentAId       = text("agent_a_id").references(Agents.id)
    val agentBId       = text("agent_b_id").references(Agents.id)
    val score          = integer("score")
    val scoreBreakdown = jsonb("score_breakdown")
    val reason         = text("reason")
    val coveringNeeds  = array<String>("covering_needs")
    val status         = text("status").default("pending")
    val approvedByA    = bool("approved_by_a").default(false)
    val approvedByB    = bool("approved_by_b").default(false)
    val contract       = jsonb("contract").nullable()
    val dismissedUntil = timestamp("dismissed_until").nullable()
    val createdAt      = timestamp("created_at")
    val updatedAt      = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object Delegations : Table("delegations") {
    val id                 = text("id")
    val matchId            = text("match_id").references(Matches.id)
    val fromAgentId        = text("from_agent_id").references(Agents.id)
    val toAgentId          = text("to_agent_id").references(Agents.id)
    val task               = text("task")
    val input              = jsonb("input")
    val callbackUrl        = text("callback_url")
    val idempotencyKey     = text("idempotency_key")
    val timeoutSeconds     = integer("timeout_seconds").default(60)
    val metadata           = jsonb("metadata")
    val status             = text("status").default("queued")
    val output             = jsonb("output").nullable()
    val error              = jsonb("error").nullable()
    val durationMs         = integer("duration_ms").nullable()
    val chainId            = text("chain_id").nullable()
    val chainDepth         = integer("chain_depth").default(0)
    val parentDelegationId = text("parent_delegation_id").nullable()
    val fallbackAgentId    = text("fallback_agent_id").nullable()
    val fallbackTriggered  = bool("fallback_triggered").default(false)
    val createdAt          = timestamp("created_at")
    val startedAt          = timestamp("started_at").nullable()
    val completedAt        = timestamp("completed_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Teams : Table("teams") {
    val id          = text("id")
    val name        = text("name")
    val description = text("description")
    val ownerEmail  = text("owner_email")
    val apiKeyHash  = text("api_key_hash")
    val public      = bool("public").default(true)
    val has         = array<String>("has")
    val needs       = array<String>("needs")
    val stats       = jsonb("stats")
    val createdAt   = timestamp("created_at")
    val updatedAt   = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object TeamMembers : Table("team_members") {
    val teamId  = text("team_id").references(Teams.id)
    val agentId = text("agent_id").references(Agents.id)
    val role    = text("role").default("member")
    val addedAt = timestamp("added_at")
    override val primaryKey = PrimaryKey(teamId, agentId)
}

object BillingRates : Table("billing_rates") {
    val agentId    = text("agent_id").references(Agents.id)
    val taskType   = text("task_type")
    val priceCents = integer("price_cents").default(0)
    val currency   = text("currency").default("usd")
    val active     = bool("active").default(true)
    val createdAt  = timestamp("created_at")
    override val primaryKey = PrimaryKey(agentId, taskType)
}

object BillingTransactions : Table("billing_transactions") {
    val id                    = text("id")
    val delegationId          = text("delegation_id").references(Delegations.id)
    val payerAgentId          = text("payer_agent_id").references(Agents.id)
    val payeeAgentId          = text("payee_agent_id").references(Agents.id)
    val amountCents           = integer("amount_cents")
    val currency              = text("currency").default("usd")
    val stripePaymentIntentId = text("stripe_payment_intent_id").nullable()
    val status                = text("status").default("pending")
    val createdAt             = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

fun Table.jsonb(name: String) = text(name)
