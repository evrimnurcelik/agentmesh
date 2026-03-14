package io.agentmesh.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─────────────────────────────────────────
// Enums
// ─────────────────────────────────────────

enum class Framework { openclaw, langchain, autogen, crewai, custom }
enum class AgentStatus { online, idle, offline }
enum class MatchStatus { pending, pending_counterpart, active, dismissed, expired }
enum class DelegationStatus { queued, running, completed, failed, timed_out }
enum class TrustTier { public, verified, private }  // v0.2

// ─────────────────────────────────────────
// v0.2: Capability with Negotiation Schemas
// ─────────────────────────────────────────

@Serializable
data class CapabilitySchema(
    val type: String,
    val required: Boolean = false,
    val description: String? = null
)

@Serializable
data class Capability(
    val id: String,
    val label: String,
    val category: String,
    @SerialName("task_types")    val taskTypes: List<String>,
    val color: String? = null,
    @SerialName("input_schema")  val inputSchema: Map<String, @Serializable Any?> = emptyMap(),   // v0.2
    @SerialName("output_schema") val outputSchema: Map<String, @Serializable Any?> = emptyMap()   // v0.2
)

// ─────────────────────────────────────────
// Agent
// ─────────────────────────────────────────

@Serializable
data class AgentStats(
    @SerialName("delegations_sent")     val delegationsSent: Int = 0,
    @SerialName("delegations_received") val delegationsReceived: Int = 0,
    @SerialName("success_count")        val successCount: Int = 0,
    @SerialName("fail_count")           val failCount: Int = 0
)

@Serializable
data class PublicAgent(
    val id: String,
    val name: String,
    val description: String,
    val framework: String,
    val has: List<String>,
    val needs: List<String>,
    val public: Boolean,
    @SerialName("trust_tier")   val trustTier: String = "public",   // v0.2
    val status: String,
    val stats: AgentStats,
    @SerialName("verified_at")  val verifiedAt: String? = null,      // v0.2
    @SerialName("created_at")   val createdAt: String,
    @SerialName("updated_at")   val updatedAt: String,
    @SerialName("matched_with") val matchedWith: List<String> = emptyList()
)

// ─────────────────────────────────────────
// Match
// ─────────────────────────────────────────

@Serializable
data class ScoreBreakdown(
    @SerialName("tool_overlap")     val toolOverlap: Int,
    @SerialName("domain_proximity") val domainProximity: Int,
    val reliability: Int
)

@Serializable
data class MatchContract(
    @SerialName("allowed_task_types") val allowedTaskTypes: List<String>,
    @SerialName("rate_limit")         val rateLimit: String,
    @SerialName("expires_at")         val expiresAt: String?,
    @SerialName("billing_enabled")    val billingEnabled: Boolean = false,  // v0.3
    @SerialName("price_per_task")     val pricePerTask: Map<String, Int> = emptyMap() // v0.3 task->cents
)

@Serializable
data class Match(
    val id: String,
    @SerialName("agent_a_id")      val agentAId: String,
    @SerialName("agent_b_id")      val agentBId: String,
    val score: Int,
    @SerialName("score_breakdown") val scoreBreakdown: ScoreBreakdown,
    val reason: String,
    @SerialName("covering_needs")  val coveringNeeds: List<String>,
    val status: String,
    @SerialName("approved_by_a")   val approvedByA: Boolean,
    @SerialName("approved_by_b")   val approvedByB: Boolean,
    val contract: MatchContract? = null,
    @SerialName("created_at")      val createdAt: String,
    @SerialName("updated_at")      val updatedAt: String
)

// ─────────────────────────────────────────
// Delegation — v0.2 chains + v0.3 fallback
// ─────────────────────────────────────────

@Serializable
data class DelegationError(
    val code: String,
    val message: String,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Int? = null,
    val retryable: Boolean
)

@Serializable
data class Delegation(
    val id: String,
    @SerialName("match_id")              val matchId: String,
    @SerialName("from_agent_id")         val fromAgentId: String,
    @SerialName("to_agent_id")           val toAgentId: String,
    val task: String,
    val input: Map<String, @Serializable Any?>,
    @SerialName("callback_url")          val callbackUrl: String,
    @SerialName("idempotency_key")       val idempotencyKey: String,
    @SerialName("timeout_seconds")       val timeoutSeconds: Int,
    val metadata: Map<String, @Serializable Any?>,
    val status: String,
    val output: Map<String, @Serializable Any?>? = null,
    val error: DelegationError? = null,
    @SerialName("duration_ms")           val durationMs: Int? = null,
    // v0.2: chains
    @SerialName("chain_id")              val chainId: String? = null,
    @SerialName("chain_depth")           val chainDepth: Int = 0,
    @SerialName("parent_delegation_id")  val parentDelegationId: String? = null,
    // v0.3: fallback
    @SerialName("fallback_agent_id")     val fallbackAgentId: String? = null,
    @SerialName("fallback_triggered")    val fallbackTriggered: Boolean = false,
    @SerialName("created_at")            val createdAt: String,
    @SerialName("started_at")            val startedAt: String? = null,
    @SerialName("completed_at")          val completedAt: String? = null
)

// v0.2: chain summary returned by GET /chains/{chainId}
@Serializable
data class DelegationChain(
    @SerialName("chain_id")   val chainId: String,
    val hops: List<ChainHop>,
    val status: String,       // overall: completed | running | failed
    @SerialName("total_duration_ms") val totalDurationMs: Int
)

@Serializable
data class ChainHop(
    val depth: Int,
    @SerialName("delegation_id") val delegationId: String,
    @SerialName("from_agent")    val fromAgent: String,
    @SerialName("to_agent")      val toAgent: String,
    val task: String,
    val status: String,
    @SerialName("duration_ms")   val durationMs: Int?
)

// ─────────────────────────────────────────
// v0.3: Teams
// ─────────────────────────────────────────

@Serializable
data class TeamStats(
    @SerialName("delegations_sent")     val delegationsSent: Int = 0,
    @SerialName("delegations_received") val delegationsReceived: Int = 0
)

@Serializable
data class Team(
    val id: String,
    val name: String,
    val description: String,
    val public: Boolean,
    val has: List<String>,
    val needs: List<String>,
    val stats: TeamStats,
    val members: List<TeamMember> = emptyList(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class TeamMember(
    @SerialName("agent_id")   val agentId: String,
    val name: String,
    val role: String,
    @SerialName("added_at")   val addedAt: String
)

// ─────────────────────────────────────────
// v0.3: Billing
// ─────────────────────────────────────────

@Serializable
data class BillingRate(
    @SerialName("agent_id")    val agentId: String,
    @SerialName("task_type")   val taskType: String,
    @SerialName("price_cents") val priceCents: Int,
    val currency: String = "usd",
    val active: Boolean = true
)

@Serializable
data class BillingTransaction(
    val id: String,
    @SerialName("delegation_id")          val delegationId: String,
    @SerialName("payer_agent_id")         val payerAgentId: String,
    @SerialName("payee_agent_id")         val payeeAgentId: String,
    @SerialName("amount_cents")           val amountCents: Int,
    val currency: String,
    @SerialName("stripe_payment_intent_id") val stripePaymentIntentId: String? = null,
    val status: String,
    @SerialName("created_at")             val createdAt: String
)

// ─────────────────────────────────────────
// API Requests / Responses
// ─────────────────────────────────────────

@Serializable
data class RegisterAgentRequest(
    val name: String,
    val description: String,
    val framework: String,
    val has: List<String>,
    val needs: List<String> = emptyList(),
    @SerialName("owner_email")  val ownerEmail: String,
    @SerialName("webhook_url")  val webhookUrl: String,
    val public: Boolean = true,
    @SerialName("trust_tier")   val trustTier: String = "public"  // v0.2
)

@Serializable
data class RegisterAgentResponse(
    @SerialName("agent_id")          val agentId: String,
    @SerialName("api_key")           val apiKey: String,
    @SerialName("created_at")        val createdAt: String,
    @SerialName("match_score_ready") val matchScoreReady: Boolean = false
)

@Serializable
data class PatchAgentRequest(
    val name: String? = null,
    val description: String? = null,
    val has: List<String>? = null,
    val needs: List<String>? = null,
    @SerialName("webhook_url")  val webhookUrl: String? = null,
    val public: Boolean? = null,
    val status: String? = null,
    @SerialName("trust_tier")   val trustTier: String? = null   // v0.2
)

@Serializable
data class DelegateRequest(
    val to: String,
    val task: String,
    val input: Map<String, @Serializable Any?>,
    @SerialName("callback_url")    val callbackUrl: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("timeout_seconds") val timeoutSeconds: Int = 60,
    val metadata: Map<String, @Serializable Any?> = emptyMap(),
    // v0.2: chain support
    @SerialName("chain_id")        val chainId: String? = null,
    @SerialName("chain_depth")     val chainDepth: Int = 0,
    @SerialName("parent_delegation_id") val parentDelegationId: String? = null,
    // v0.3: fallback agent
    @SerialName("fallback_agent_id") val fallbackAgentId: String? = null
)

@Serializable
data class DelegateResponse(
    @SerialName("delegation_id")         val delegationId: String,
    @SerialName("chain_id")              val chainId: String? = null,    // v0.2
    val status: String = "queued",
    @SerialName("estimated_response_ms") val estimatedResponseMs: Int = 1500
)

@Serializable
data class WebhookPayload(
    val event: String,
    @SerialName("delegation_id") val delegationId: String,
    @SerialName("chain_id")      val chainId: String? = null,   // v0.2
    val status: String,
    val output: Map<String, @Serializable Any?>? = null,
    val error: DelegationError? = null,
    @SerialName("duration_ms")   val durationMs: Int,
    val metadata: Map<String, @Serializable Any?>,
    val timestamp: String
)

// v0.3: Teams
@Serializable
data class CreateTeamRequest(
    val name: String,
    val description: String,
    @SerialName("owner_email") val ownerEmail: String,
    val public: Boolean = true
)

@Serializable
data class CreateTeamResponse(
    @SerialName("team_id") val teamId: String,
    @SerialName("api_key") val apiKey: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class AddTeamMemberRequest(
    @SerialName("agent_id") val agentId: String,
    val role: String = "member"
)

// v0.3: Billing
@Serializable
data class SetBillingRateRequest(
    @SerialName("task_type")   val taskType: String,
    @SerialName("price_cents") val priceCents: Int,
    val currency: String = "usd"
)

@Serializable
data class ApiError(
    val error: String,
    val code: String,
    val message: String
)

@Serializable
data class StatusUpdate(val status: String)
