// ─────────────────────────────────────────
// Core Domain Types
// ─────────────────────────────────────────

export type Framework = "openclaw" | "langchain" | "autogen" | "crewai" | "custom";
export type AgentStatus = "online" | "idle" | "offline";
export type MatchStatus = "pending" | "pending_counterpart" | "active" | "dismissed" | "expired";
export type DelegationStatus = "queued" | "running" | "completed" | "failed" | "timed_out";

export interface Capability {
  id: string;
  label: string;
  category: "communication" | "data" | "storage" | "devtools" | "finance" | "ai";
  task_types: string[];
  color?: string;
}

export interface AgentStats {
  delegations_sent: number;
  delegations_received: number;
  success_count: number;
  fail_count: number;
}

export interface Agent {
  id: string;
  name: string;
  description: string;
  framework: Framework;
  has: string[];
  needs: string[];
  owner_email: string;
  webhook_url: string;
  public: boolean;
  status: AgentStatus;
  last_ping?: string;
  stats: AgentStats;
  created_at: string;
  updated_at: string;
}

// Public-facing agent (no sensitive fields)
export type PublicAgent = Omit<Agent, "owner_email" | "webhook_url">;

export interface MatchContract {
  allowed_task_types: string[];
  rate_limit: string;
  expires_at: string | null;
}

export interface ScoreBreakdown {
  tool_overlap: number;
  domain_proximity: number;
  reliability: number;
}

export interface Match {
  id: string;
  agent_a_id: string;
  agent_b_id: string;
  score: number;
  score_breakdown: ScoreBreakdown;
  reason: string;
  covering_needs: string[];
  status: MatchStatus;
  approved_by_a: boolean;
  approved_by_b: boolean;
  contract?: MatchContract;
  created_at: string;
  updated_at: string;
}

export interface MatchWithAgents extends Match {
  agent_a: PublicAgent;
  agent_b: PublicAgent;
}

export interface DelegationError {
  code: string;
  message: string;
  retry_after_seconds?: number;
  retryable: boolean;
}

export interface Delegation {
  id: string;
  match_id: string;
  from_agent_id: string;
  to_agent_id: string;
  task: string;
  input: Record<string, unknown>;
  callback_url: string;
  idempotency_key: string;
  timeout_seconds: number;
  metadata: Record<string, unknown>;
  status: DelegationStatus;
  output?: Record<string, unknown>;
  error?: DelegationError;
  duration_ms?: number;
  created_at: string;
  started_at?: string;
  completed_at?: string;
}

// ─────────────────────────────────────────
// API Request/Response Types
// ─────────────────────────────────────────

export interface RegisterAgentRequest {
  name: string;
  description: string;
  framework: Framework;
  has: string[];
  needs?: string[];
  owner_email: string;
  webhook_url: string;
  public?: boolean;
}

export interface RegisterAgentResponse {
  agent_id: string;
  api_key: string;
  created_at: string;
  match_score_ready: boolean;
}

export interface DelegateRequest {
  to: string;
  task: string;
  input: Record<string, unknown>;
  callback_url: string;
  idempotency_key: string;
  timeout_seconds?: number;
  metadata?: Record<string, unknown>;
}

export interface DelegateResponse {
  delegation_id: string;
  status: "queued";
  estimated_response_ms: number;
}

// Webhook payload sent to callback_url
export interface WebhookPayload {
  event: "delegation.completed" | "delegation.failed" | "delegation.timeout";
  delegation_id: string;
  status: DelegationStatus;
  output?: Record<string, unknown>;
  error?: DelegationError;
  duration_ms: number;
  metadata: Record<string, unknown>;
  timestamp: string;
}

export interface ApiError {
  error: string;
  code: string;
  message: string;
}
