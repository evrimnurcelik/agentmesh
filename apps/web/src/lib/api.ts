const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

async function apiFetch<T>(
  path: string,
  options: RequestInit & { apiKey?: string } = {}
): Promise<T> {
  const { apiKey, ...init } = options;
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(init.headers as Record<string, string> ?? {}),
  };
  if (apiKey) headers["Authorization"] = `Bearer ${apiKey}`;

  const res = await fetch(`${API_URL}${path}`, { ...init, headers });
  const data = await res.json();

  if (!res.ok) {
    throw new ApiError(data.message ?? "Request failed", data.code ?? "unknown", res.status);
  }
  return data as T;
}

export class ApiError extends Error {
  constructor(message: string, public code: string, public status: number) {
    super(message);
  }
}

// ─── Agents ──────────────────────────────────────────────────

export interface AgentStats {
  delegations_sent: number;
  delegations_received: number;
  success_count: number;
  fail_count: number;
}

export interface PublicAgent {
  id: string;
  name: string;
  description: string;
  framework: string;
  has: string[];
  needs: string[];
  public: boolean;
  status: "online" | "idle" | "offline";
  stats: AgentStats;
  created_at: string;
  updated_at: string;
  matched_with?: string[];
}

export interface RegisterAgentRequest {
  name: string;
  description: string;
  framework: string;
  has: string[];
  needs: string[];
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

export const agentsApi = {
  list: (params?: { framework?: string; has?: string; needs?: string; limit?: number }) => {
    const q = new URLSearchParams(params as Record<string, string>).toString();
    return apiFetch<{ agents: PublicAgent[]; count: number }>(`/agents${q ? `?${q}` : ""}`);
  },
  get: (id: string) => apiFetch<PublicAgent>(`/agents/${id}`),
  register: (body: RegisterAgentRequest) =>
    apiFetch<RegisterAgentResponse>("/agents", { method: "POST", body: JSON.stringify(body) }),
  patch: (id: string, body: Partial<PublicAgent>, apiKey: string) =>
    apiFetch(`/agents/${id}`, { method: "PATCH", body: JSON.stringify(body), apiKey }),
  delete: (id: string, apiKey: string) =>
    apiFetch(`/agents/${id}`, { method: "DELETE", apiKey }),
  heartbeat: (id: string, status: string, apiKey: string) =>
    apiFetch(`/agents/${id}/status`, { method: "PATCH", body: JSON.stringify({ status }), apiKey }),
};

// ─── Capabilities ────────────────────────────────────────────

export interface Capability {
  id: string;
  label: string;
  category: string;
  task_types: string[];
  color?: string;
}

export const capabilitiesApi = {
  list: () => apiFetch<{ capabilities: Capability[]; grouped: Record<string, Capability[]> }>("/capabilities"),
};

// ─── Matches ─────────────────────────────────────────────────

export interface MatchEntry {
  match_id: string;
  agent: { agent_id: string; name: string; owner: string; framework: string };
  score: number;
  score_breakdown: { tool_overlap: number; domain_proximity: number; reliability: number };
  reason: string;
  covering_needs: string[];
  status: string;
  created_at: string;
}

export interface MatchContract {
  allowed_task_types: string[];
  rate_limit: string;
  expires_at: string | null;
}

export const matchesApi = {
  list: (apiKey: string, params?: { min_score?: number; status?: string; limit?: number }) => {
    const q = new URLSearchParams(params as Record<string, string>).toString();
    return apiFetch<{ matches: MatchEntry[]; total: number }>(`/matches${q ? `?${q}` : ""}`, { apiKey });
  },
  approve: (matchId: string, apiKey: string) =>
    apiFetch(`/matches/${matchId}/approve`, { method: "POST", apiKey }),
  dismiss: (matchId: string, apiKey: string) =>
    apiFetch(`/matches/${matchId}/dismiss`, { method: "POST", apiKey }),
};

// ─── Delegations ─────────────────────────────────────────────

export interface Delegation {
  id: string;
  match_id: string;
  from_agent_id: string;
  to_agent_id: string;
  task: string;
  input: Record<string, unknown>;
  callback_url: string;
  idempotency_key: string;
  status: string;
  output?: Record<string, unknown>;
  error?: { code: string; message: string; retryable: boolean };
  duration_ms?: number;
  created_at: string;
  completed_at?: string;
}

export const delegationsApi = {
  list: (apiKey: string, params?: { direction?: string; status?: string; limit?: number }) => {
    const q = new URLSearchParams(params as Record<string, string>).toString();
    return apiFetch<{ delegations: Delegation[]; count: number }>(`/delegations${q ? `?${q}` : ""}`, { apiKey });
  },
  get: (id: string, apiKey: string) => apiFetch<Delegation>(`/delegations/${id}`, { apiKey }),
  send: (body: {
    to: string; task: string; input: Record<string, unknown>;
    callback_url: string; idempotency_key: string; timeout_seconds?: number;
    metadata?: Record<string, unknown>;
  }, apiKey: string) =>
    apiFetch("/delegate", { method: "POST", body: JSON.stringify(body), apiKey }),
  replay: (delegationId: string, apiKey: string) =>
    apiFetch<{ delegation_id: string; chain_id?: string; status: string }>(`/delegations/${delegationId}/replay`, { method: "POST", apiKey }),
};

// ─── MCP Servers (v0.4) ──────────────────────────────────────

export const mcpApi = {
  list: (agentId: string) =>
    apiFetch<{ servers: Record<string, unknown>[]; count: number }>(`/agents/${agentId}/mcp-servers`),
  register: (agentId: string, body: { name: string; url: string; auth_type?: string; auth_secret?: string }, apiKey: string) =>
    apiFetch<{ mcp_id: string }>(`/agents/${agentId}/mcp-servers`, { method: "POST", body: JSON.stringify(body), apiKey }),
  sync: (agentId: string, mcpId: string, apiKey: string) =>
    apiFetch<{ tools_discovered: string[]; count: number }>(`/agents/${agentId}/mcp-servers/${mcpId}/sync`, { method: "POST", apiKey }),
  remove: (agentId: string, mcpId: string, apiKey: string) =>
    apiFetch(`/agents/${agentId}/mcp-servers/${mcpId}`, { method: "DELETE", apiKey }),
};

// ─── Orgs (v0.4) ────────────────────────────────────────────

export const orgsApi = {
  create: (body: { name: string; slug: string }, apiKey?: string) =>
    apiFetch<{ org_id: string; slug: string; created_at: string }>("/orgs", { method: "POST", body: JSON.stringify(body), apiKey }),
  get: (orgId: string) => apiFetch<Record<string, unknown>>(`/orgs/${orgId}`),
  agents: (orgId: string) => apiFetch<{ agents: Record<string, unknown>[]; count: number }>(`/orgs/${orgId}/agents`),
  delegations: (orgId: string) => apiFetch<{ delegations: Record<string, unknown>[]; count: number }>(`/orgs/${orgId}/delegations`),
  inviteMember: (orgId: string, email: string, role: string) =>
    apiFetch(`/orgs/${orgId}/members`, { method: "POST", body: JSON.stringify({ email, role }) }),
  removeMember: (orgId: string, email: string) =>
    apiFetch(`/orgs/${orgId}/members/${email}`, { method: "DELETE" }),
  createKey: (orgId: string, name: string, scopes: string[]) =>
    apiFetch<{ key_id: string; api_key: string }>(`/orgs/${orgId}/keys`, { method: "POST", body: JSON.stringify({ name, scopes }) }),
  revokeKey: (orgId: string, keyId: string) =>
    apiFetch(`/orgs/${orgId}/keys/${keyId}`, { method: "DELETE" }),
};

// ─── Marketplace (v0.4) ──────────────────────────────────────

export const marketplaceApi = {
  list: (params?: { category?: string; sort?: string; limit?: number }) => {
    const q = new URLSearchParams(params as Record<string, string>).toString();
    return apiFetch<{ agents: Record<string, unknown>[]; count: number }>(`/marketplace${q ? `?${q}` : ""}`);
  },
  get: (agentId: string) => apiFetch<Record<string, unknown>>(`/marketplace/${agentId}`),
  listAgent: (body: { tagline?: string; categories?: string[] }, apiKey: string) =>
    apiFetch("/marketplace/list", { method: "POST", body: JSON.stringify(body), apiKey }),
  submitReview: (body: { agent_id: string; rating: number; comment?: string; delegation_id?: string }, apiKey: string) =>
    apiFetch("/marketplace/reviews", { method: "POST", body: JSON.stringify(body), apiKey }),
  getReviews: (agentId: string) =>
    apiFetch<{ reviews: Record<string, unknown>[]; count: number }>(`/marketplace/${agentId}/reviews`),
};

// ─── Agent Versions (v0.4) ───────────────────────────────────

export interface AgentVersion {
  id: string;
  agent_id: string;
  version: number;
  has: string[];
  needs: string[];
  description: string;
  changelog?: string;
  created_at: string;
}

export const versionsApi = {
  list: (agentId: string) =>
    apiFetch<{ versions: AgentVersion[]; count: number }>(`/agents/${agentId}/versions`),
  get: (agentId: string, v: number) =>
    apiFetch<AgentVersion>(`/agents/${agentId}/versions/${v}`),
  publish: (agentId: string, changelog: string | undefined, apiKey: string) =>
    apiFetch<{ version: number; agent_id: string }>(`/agents/${agentId}/versions`,
      { method: "POST", body: JSON.stringify({ changelog }), apiKey }),
};

// ─── Health (v0.4) ───────────────────────────────────────────

export interface HealthSummary {
  agent_id: string;
  current_status: string;
  uptime_7d: number | null;
  uptime_30d: number | null;
  avg_latency_ms: number | null;
  checks: { id: string; status: string; latency_ms: number | null; checked_at: string }[];
}

export const healthApi = {
  summary: (agentId: string) => apiFetch<HealthSummary>(`/agents/${agentId}/health`),
  history: (agentId: string, limit?: number) =>
    apiFetch<{ checks: { id: string; status: string; latency_ms: number | null; checked_at: string }[]; count: number }>(
      `/agents/${agentId}/health/history${limit ? `?limit=${limit}` : ""}`),
};

// ─── Leaderboard (v0.4) ──────────────────────────────────────

export const leaderboardApi = {
  delegations: () => apiFetch<{ agents: Record<string, unknown>[]; updated_at: string }>("/leaderboard/delegations"),
  reliability: () => apiFetch<{ agents: Record<string, unknown>[]; updated_at: string }>("/leaderboard/reliability"),
  wanted: () => apiFetch<{ capabilities: { tool: string; demanded_by: number }[]; updated_at: string }>("/leaderboard/wanted"),
};

// ─── Analytics (v0.4) ────────────────────────────────────────

export const analyticsApi = {
  delegations: (days: number, apiKey: string) =>
    apiFetch<{ series: { date: string; total: number; completed: number; failed: number; running: number }[]; summary: { total: number; success_rate: number; avg_latency_ms: number } }>(
      `/analytics/delegations?days=${days}`, { apiKey }),
  latency: (days: number, apiKey: string) =>
    apiFetch<{ by_task: { task: string; count: number; avg_ms: number; p50_ms: number | null; p95_ms: number | null; p99_ms: number | null }[] }>(
      `/analytics/latency?days=${days}`, { apiKey }),
  cost: (days: number, apiKey: string) =>
    apiFetch<{ series: { date: string; earned_cents: number; spent_cents: number }[] }>(
      `/analytics/cost?days=${days}`, { apiKey }),
  reliability: (days: number, apiKey: string) =>
    apiFetch<{ series: { date: string; total: number; success: number; rate: number }[] }>(
      `/analytics/reliability?days=${days}`, { apiKey }),
  topPairs: (days: number, apiKey: string) =>
    apiFetch<{ pairs: { pair: string; count: number; completed: number; avg_duration_ms: number }[] }>(
      `/analytics/top-pairs?days=${days}`, { apiKey }),
};

// ─── Chains (v0.2) ───────────────────────────────────────────

export interface ChainHop {
  depth: number;
  delegation_id: string;
  from_agent: string;
  to_agent: string;
  task: string;
  status: string;
  duration_ms?: number;
}

export interface DelegationChain {
  chain_id: string;
  hops: ChainHop[];
  status: string;
  total_duration_ms: number;
}

export const chainsApi = {
  get: (chainId: string, apiKey: string) =>
    apiFetch<DelegationChain>(`/chains/${chainId}`, { apiKey }),
  list: (apiKey: string) =>
    apiFetch<{ chains: DelegationChain[]; count: number }>("/chains", { apiKey }),
};

// ─── Teams (v0.3) ────────────────────────────────────────────

export interface TeamMember { agent_id: string; name: string; role: string; added_at: string; }
export interface Team {
  id: string; name: string; description: string; public: boolean;
  has: string[]; needs: string[]; stats: { delegations_sent: number; delegations_received: number };
  members: TeamMember[]; created_at: string; updated_at: string;
}

export const teamsApi = {
  list: () => apiFetch<{ teams: Team[]; count: number }>("/teams"),
  get: (id: string) => apiFetch<Team>(`/teams/${id}`),
  create: (body: { name: string; description: string; owner_email: string; public?: boolean }) =>
    apiFetch<{ team_id: string; api_key: string; created_at: string }>("/teams",
      { method: "POST", body: JSON.stringify(body) }),
  addMember: (teamId: string, agentId: string, role: string, apiKey: string) =>
    apiFetch(`/teams/${teamId}/members`,
      { method: "POST", body: JSON.stringify({ agent_id: agentId, role }), apiKey }),
  removeMember: (teamId: string, agentId: string, apiKey: string) =>
    apiFetch(`/teams/${teamId}/members/${agentId}`, { method: "DELETE", apiKey }),
};

// ─── Billing (v0.3) ──────────────────────────────────────────

export interface BillingRate { agent_id: string; task_type: string; price_cents: number; currency: string; active: boolean; }
export interface BillingTransaction {
  id: string; delegation_id: string; payer_agent_id: string; payee_agent_id: string;
  amount_cents: number; currency: string; stripe_payment_intent_id?: string;
  status: string; created_at: string;
}

export const billingApi = {
  getRates: (apiKey: string) =>
    apiFetch<{ rates: BillingRate[]; agent_id: string }>("/billing/rates", { apiKey }),
  setRate: (taskType: string, priceCents: number, apiKey: string) =>
    apiFetch("/billing/rates",
      { method: "POST", body: JSON.stringify({ task_type: taskType, price_cents: priceCents }), apiKey }),
  deleteRate: (taskType: string, apiKey: string) =>
    apiFetch(`/billing/rates/${taskType}`, { method: "DELETE", apiKey }),
  getTransactions: (apiKey: string, direction?: string) =>
    apiFetch<{ transactions: BillingTransaction[]; count: number; total_earned_cents: number; total_spent_cents: number }>(
      `/billing/transactions${direction ? `?direction=${direction}` : ""}`, { apiKey }),
  getAgentRates: (agentId: string) =>
    apiFetch<{ agent_id: string; rates: { task_type: string; price_cents: number }[] }>(`/billing/rates/agent/${agentId}`),
};
