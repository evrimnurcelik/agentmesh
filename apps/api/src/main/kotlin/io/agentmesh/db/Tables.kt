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
    val stripeAccountId        = text("stripe_account_id").nullable()
    val currentVersion         = integer("current_version").default(1)               // v0.4
    val marketplaceListed      = bool("marketplace_listed").default(false)            // v0.4
    val marketplaceTagline     = text("marketplace_tagline").nullable()               // v0.4
    val marketplaceCategories  = array<String>("marketplace_categories")              // v0.4
    val orgId                  = text("org_id").nullable()                            // v0.4
    val createdAt              = timestamp("created_at")
    val updatedAt              = timestamp("updated_at")
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
    val agentAVersion  = integer("agent_a_version").default(1)           // v0.4
    val agentBVersion  = integer("agent_b_version").default(1)           // v0.4
    val sla            = jsonb("sla").nullable()                         // v0.4
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
    val streaming          = bool("streaming").default(false)               // v0.4
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

// ─── v0.4: Health Monitoring ─────────────────────────────

object AgentHealthChecks : Table("agent_health_checks") {
    val id        = text("id")
    val agentId   = text("agent_id").references(Agents.id)
    val status    = text("status")
    val latencyMs = integer("latency_ms").nullable()
    val checkedAt = timestamp("checked_at")
    override val primaryKey = PrimaryKey(id)
}

// ─── v0.4: Agent Versioning ─────────────────────────────

object AgentVersions : Table("agent_versions") {
    val id          = text("id")
    val agentId     = text("agent_id").references(Agents.id)
    val version     = integer("version")
    val has         = array<String>("has")
    val needs       = array<String>("needs")
    val description = text("description")
    val changelog   = text("changelog").nullable()
    val createdAt   = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

// ─── v0.4: Marketplace ──────────────────────────────────

object MarketplaceReviews : Table("marketplace_reviews") {
    val id              = text("id")
    val agentId         = text("agent_id").references(Agents.id)
    val reviewerAgentId = text("reviewer_agent_id").references(Agents.id)
    val rating          = integer("rating")
    val comment         = text("comment").nullable()
    val delegationId    = text("delegation_id").nullable()
    val createdAt       = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object PlatformFees : Table("platform_fees") {
    val id                    = text("id")
    val billingTransactionId  = text("billing_transaction_id").references(BillingTransactions.id)
    val feeCents              = integer("fee_cents")
    val currency              = text("currency").default("usd")
    val createdAt             = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

// ─── v0.4: Organizations ────────────────────────────────

object Orgs : Table("orgs") {
    val id               = text("id")
    val name             = text("name")
    val slug             = text("slug")
    val plan             = text("plan").default("free")
    val stripeCustomerId = text("stripe_customer_id").nullable()
    val createdAt        = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object OrgMembers : Table("org_members") {
    val orgId     = text("org_id").references(Orgs.id)
    val email     = text("email")
    val role      = text("role")
    val invitedAt = timestamp("invited_at")
    val joinedAt  = timestamp("joined_at").nullable()
    override val primaryKey = PrimaryKey(orgId, email)
}

object OrgApiKeys : Table("org_api_keys") {
    val id         = text("id")
    val orgId      = text("org_id").references(Orgs.id)
    val name       = text("name")
    val apiKeyHash = text("api_key_hash")
    val scopes     = array<String>("scopes")
    val createdAt  = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

// ─── v0.4: SLA Violations ───────────────────────────────

object SlaViolations : Table("sla_violations") {
    val id               = text("id")
    val matchId          = text("match_id").references(Matches.id)
    val violatingAgentId = text("violating_agent_id").references(Agents.id)
    val violationType    = text("violation_type")
    val measuredValue    = decimal("measured_value", 10, 4)
    val threshold        = decimal("threshold", 10, 4)
    val windowStart      = timestamp("window_start")
    val windowEnd        = timestamp("window_end")
    val resolved         = bool("resolved").default(false)
    val createdAt        = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

// ─── v0.4: MCP Server Registry ──────────────────────────

object McpServers : Table("mcp_servers") {
    val id         = text("id")
    val agentId    = text("agent_id").references(Agents.id)
    val name       = text("name")
    val url        = text("url")
    val authType   = text("auth_type").default("none")
    val authSecret = text("auth_secret").nullable()
    val tools      = jsonb("tools")
    val lastSynced = timestamp("last_synced").nullable()
    val createdAt  = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

// ─── v0.4: Delegation Events (Streaming) ────────────────

object DelegationEvents : Table("delegation_events") {
    val id           = text("id")
    val delegationId = text("delegation_id").references(Delegations.id)
    val sequence     = integer("sequence")
    val eventType    = text("event_type")
    val data         = jsonb("data")
    val createdAt    = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

fun Table.jsonb(name: String) = text(name)
