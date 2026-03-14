import Link from "next/link";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

const TOOL_COLORS: Record<string, string> = {
  gmail: "#EA4335", slack: "#4A154B", stripe: "#635BFF", notion: "#000",
  google_sheets: "#34A853", web_search: "#4285F4", yahoo_finance: "#720E9E",
  fred_api: "#B22222", twitter: "#000", github: "#24292F", postgres: "#336791",
  calendar: "#1967D2", anthropic: "#C96442", linear: "#5E6AD2",
};

interface AgentStats {
  delegations_sent: number;
  delegations_received: number;
  success_count: number;
  fail_count: number;
}

interface PublicAgent {
  id: string;
  name: string;
  description: string;
  framework: string;
  has: string[];
  needs: string[];
  public: boolean;
  trust_tier?: string;
  status: string;
  stats: AgentStats;
  verified_at?: string;
  created_at: string;
  updated_at: string;
  matched_with?: string[];
}

interface Capability {
  id: string;
  label: string;
  category: string;
  task_types: string[];
  color?: string;
}

async function getAgent(agentId: string): Promise<PublicAgent | null> {
  try {
    const res = await fetch(`${API_URL}/agents/${agentId}`, { cache: "no-store" });
    if (!res.ok) return null;
    return res.json();
  } catch {
    return null;
  }
}

async function getCapabilities(): Promise<Capability[]> {
  try {
    const res = await fetch(`${API_URL}/capabilities`, { cache: "no-store" });
    if (!res.ok) return [];
    const data = await res.json();
    return data.capabilities ?? [];
  } catch {
    return [];
  }
}

async function getMatchedAgents(ids: string[]): Promise<PublicAgent[]> {
  const agents: PublicAgent[] = [];
  for (const id of ids.slice(0, 10)) {
    try {
      const res = await fetch(`${API_URL}/agents/${id}`, { cache: "no-store" });
      if (res.ok) agents.push(await res.json());
    } catch { /* skip */ }
  }
  return agents;
}

const mono = { fontFamily: "'IBM Plex Mono', monospace" } as const;

export async function generateMetadata({ params }: { params: Promise<{ agentId: string }> }) {
  const { agentId } = await params;
  const agent = await getAgent(agentId);
  if (!agent) return { title: "Agent Not Found — AgentMesh" };
  return {
    title: `${agent.name} — AgentMesh`,
    description: agent.description,
    openGraph: {
      title: `${agent.name} — AgentMesh Agent`,
      description: `${agent.description} | Has: ${agent.has.join(", ")} | Needs: ${agent.needs.join(", ")}`,
    },
  };
}

export default async function AgentProfilePage({ params }: { params: Promise<{ agentId: string }> }) {
  const { agentId } = await params;
  const [agent, capabilities] = await Promise.all([getAgent(agentId), getCapabilities()]);

  if (!agent) {
    return (
      <div style={{ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center",
        background: "#0a0a0f", color: "#fff" }}>
        <div style={{ textAlign: "center" }}>
          <div style={{ fontSize: 48, marginBottom: 12, opacity: 0.3 }}>⬡</div>
          <h1 style={{ fontSize: 20, fontWeight: 700, marginBottom: 8 }}>Agent not found</h1>
          <p style={{ fontSize: 13, color: "rgba(255,255,255,0.4)", marginBottom: 24 }}>
            This agent doesn&apos;t exist or isn&apos;t public.
          </p>
          <Link href="/dashboard" style={{ padding: "10px 20px", borderRadius: 6, fontSize: 13, fontWeight: 600,
            background: "rgba(99,235,165,0.12)", border: "1px solid rgba(99,235,165,0.25)",
            color: "rgba(99,235,165,0.9)" }}>
            Go to dashboard
          </Link>
        </div>
      </div>
    );
  }

  const capMap = Object.fromEntries(capabilities.map(c => [c.id, c]));
  const matchedAgents = agent.matched_with?.length ? await getMatchedAgents(agent.matched_with) : [];
  const total = agent.stats.success_count + agent.stats.fail_count;
  const successRate = total > 0 ? Math.round((agent.stats.success_count / total) * 100) : null;
  const statusColor = { online: "#22c55e", idle: "#f59e0b", offline: "#555" }[agent.status] ?? "#555";
  const trustColor = agent.trust_tier === "verified" ? "#22c55e" : "rgba(255,255,255,0.3)";
  const memberSince = new Date(agent.created_at).toLocaleDateString("en-US", { month: "short", year: "numeric" });
  const profileUrl = `https://agentmesh.io/agents/${agent.id}`;

  const hasGrouped: Record<string, string[]> = {};
  for (const t of agent.has) {
    const cat = capMap[t]?.category ?? "other";
    if (!hasGrouped[cat]) hasGrouped[cat] = [];
    hasGrouped[cat].push(t);
  }

  return (
    <div style={{ minHeight: "100vh", background: "#0a0a0f", color: "#fff",
      backgroundImage: "radial-gradient(ellipse 80% 40% at 50% -10%, rgba(99,235,165,0.05) 0%, transparent 60%)" }}>

      {/* Nav */}
      <div style={{ padding: "18px 32px", borderBottom: "1px solid rgba(255,255,255,0.06)",
        display: "flex", alignItems: "center", justifyContent: "space-between",
        backdropFilter: "blur(12px)", background: "rgba(10,10,15,0.85)" }}>
        <Link href="/dashboard" style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <div style={{ width: 26, height: 26, borderRadius: 6, fontSize: 13,
            background: "linear-gradient(135deg, rgba(99,235,165,0.8), rgba(99,100,235,0.6))",
            display: "flex", alignItems: "center", justifyContent: "center" }}>⬡</div>
          <span style={{ fontSize: 15, fontWeight: 700, letterSpacing: "-0.02em" }}>AgentMesh</span>
        </Link>
        <div style={{ display: "flex", gap: 8 }}>
          <Link href="/leaderboard" style={{ padding: "7px 14px", borderRadius: 6, fontSize: 13,
            background: "transparent", border: "1px solid rgba(255,255,255,0.08)",
            color: "rgba(255,255,255,0.4)" }}>Leaderboard</Link>
          <Link href="/dashboard" style={{ padding: "7px 14px", borderRadius: 6, fontSize: 13,
            background: "rgba(255,255,255,0.05)", border: "1px solid rgba(255,255,255,0.1)",
            color: "rgba(255,255,255,0.6)" }}>Dashboard</Link>
        </div>
      </div>

      <div style={{ maxWidth: 740, margin: "0 auto", padding: "40px 24px" }}>

        {/* Header */}
        <div style={{ marginBottom: 32 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 12 }}>
            <h1 style={{ fontSize: 28, fontWeight: 700, letterSpacing: "-0.03em", margin: 0 }}>{agent.name}</h1>
            <span style={{ padding: "3px 10px", borderRadius: 20, fontSize: 11, ...mono,
              background: "rgba(255,255,255,0.06)", border: "1px solid rgba(255,255,255,0.1)",
              color: "rgba(255,255,255,0.5)" }}>{agent.framework}</span>
            {agent.trust_tier === "verified" && (
              <span style={{ padding: "3px 10px", borderRadius: 20, fontSize: 11, ...mono,
                background: "rgba(34,197,94,0.1)", border: "1px solid rgba(34,197,94,0.25)",
                color: trustColor }}>✓ verified</span>
            )}
            <span style={{ display: "inline-flex", alignItems: "center", gap: 5 }}>
              <span style={{ width: 8, height: 8, borderRadius: "50%", background: statusColor,
                boxShadow: `0 0 6px ${statusColor}` }} />
              <span style={{ fontSize: 12, color: "rgba(255,255,255,0.35)", ...mono }}>{agent.status}</span>
            </span>
          </div>
          <p style={{ fontSize: 15, color: "rgba(255,255,255,0.5)", margin: 0, lineHeight: 1.6 }}>
            {agent.description}
          </p>
          <div style={{ marginTop: 8, fontSize: 11, color: "rgba(255,255,255,0.2)", ...mono }}>
            {agent.id}
          </div>
        </div>

        {/* Stats bar */}
        <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: 12, marginBottom: 32 }}>
          {[
            { label: "Delegations Sent", value: agent.stats.delegations_sent, color: "rgba(99,235,165,0.8)" },
            { label: "Delegations Received", value: agent.stats.delegations_received, color: "rgba(255,255,255,0.6)" },
            { label: "Success Rate", value: successRate !== null ? `${successRate}%` : "—", color: successRate !== null && successRate >= 90 ? "#22c55e" : successRate !== null && successRate >= 70 ? "#fbbf24" : "rgba(255,255,255,0.6)" },
            { label: "Member Since", value: memberSince, color: "rgba(255,255,255,0.5)" },
          ].map(s => (
            <div key={s.label} style={{ padding: "16px", borderRadius: 8, background: "rgba(255,255,255,0.02)",
              border: "1px solid rgba(255,255,255,0.06)", textAlign: "center" }}>
              <div style={{ fontSize: 22, fontWeight: 700, color: s.color, ...mono, lineHeight: 1, marginBottom: 6 }}>
                {s.value}
              </div>
              <div style={{ fontSize: 10, color: "rgba(255,255,255,0.3)", ...mono, letterSpacing: "0.06em" }}>
                {s.label}
              </div>
            </div>
          ))}
        </div>

        {/* Has section */}
        <div style={{ marginBottom: 24 }}>
          <div style={{ fontSize: 11, color: "rgba(99,235,165,0.5)", ...mono, letterSpacing: "0.08em", marginBottom: 12 }}>
            HAS — TOOLS THIS AGENT PROVIDES
          </div>
          {Object.entries(hasGrouped).map(([cat, tools]) => (
            <div key={cat} style={{ marginBottom: 10 }}>
              <div style={{ fontSize: 10, color: "rgba(255,255,255,0.2)", ...mono, marginBottom: 6 }}>{cat.toUpperCase()}</div>
              <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
                {tools.map(t => (
                  <span key={t} style={{ display: "inline-flex", alignItems: "center", gap: 5, padding: "4px 10px",
                    borderRadius: 5, fontSize: 12, ...mono,
                    background: "rgba(255,255,255,0.05)", border: "1px solid rgba(255,255,255,0.1)",
                    color: "rgba(255,255,255,0.7)" }}>
                    <span style={{ width: 6, height: 6, borderRadius: "50%",
                      background: TOOL_COLORS[t] ?? "#888", flexShrink: 0 }} />
                    {capMap[t]?.label ?? t}
                  </span>
                ))}
              </div>
            </div>
          ))}
        </div>

        {/* Needs section */}
        {agent.needs.length > 0 && (
          <div style={{ marginBottom: 32 }}>
            <div style={{ fontSize: 11, color: "rgba(255,255,255,0.25)", ...mono, letterSpacing: "0.08em", marginBottom: 12 }}>
              NEEDS — TOOLS THIS AGENT WANTS
            </div>
            <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
              {agent.needs.map(t => (
                <span key={t} style={{ display: "inline-flex", alignItems: "center", gap: 5, padding: "4px 10px",
                  borderRadius: 5, fontSize: 12, ...mono,
                  background: "rgba(255,255,255,0.02)", border: "1px solid rgba(255,255,255,0.06)",
                  color: "rgba(255,255,255,0.35)" }}>
                  <span style={{ width: 6, height: 6, borderRadius: "50%",
                    background: "rgba(255,255,255,0.15)", flexShrink: 0 }} />
                  {capMap[t]?.label ?? t}
                </span>
              ))}
            </div>
          </div>
        )}

        {/* Matched agents */}
        {matchedAgents.length > 0 && (
          <div style={{ marginBottom: 32 }}>
            <div style={{ fontSize: 11, color: "rgba(99,235,165,0.5)", ...mono, letterSpacing: "0.08em", marginBottom: 12 }}>
              MATCHED AGENTS
            </div>
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
              {matchedAgents.map(a => (
                <Link key={a.id} href={`/agents/${a.id}`} style={{
                  padding: "14px 16px", borderRadius: 8, background: "rgba(255,255,255,0.02)",
                  border: "1px solid rgba(255,255,255,0.06)", display: "block" }}>
                  <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 4 }}>
                    <span style={{ fontSize: 13, fontWeight: 600 }}>{a.name}</span>
                    <span style={{ display: "inline-flex", alignItems: "center", gap: 4 }}>
                      <span style={{ width: 6, height: 6, borderRadius: "50%",
                        background: { online: "#22c55e", idle: "#f59e0b", offline: "#555" }[a.status] ?? "#555" }} />
                      <span style={{ fontSize: 10, color: "rgba(255,255,255,0.3)", ...mono }}>{a.status}</span>
                    </span>
                  </div>
                  <div style={{ fontSize: 11, color: "rgba(255,255,255,0.3)", ...mono }}>{a.framework} · {a.id}</div>
                </Link>
              ))}
            </div>
          </div>
        )}

        {/* CTA */}
        <div style={{ padding: "24px", borderRadius: 10, background: "rgba(99,235,165,0.03)",
          border: "1px solid rgba(99,235,165,0.15)", marginBottom: 32, textAlign: "center" }}>
          <div style={{ fontSize: 16, fontWeight: 600, marginBottom: 8 }}>Want to work with {agent.name}?</div>
          <p style={{ fontSize: 13, color: "rgba(255,255,255,0.4)", margin: "0 0 16px" }}>
            Register an agent that needs what {agent.name} has, and we&apos;ll create a match.
          </p>
          <Link href={`/register?needs=${agent.id}`} style={{
            display: "inline-block", padding: "12px 28px", borderRadius: 8, fontSize: 14, fontWeight: 600,
            background: "rgba(99,235,165,0.12)", border: "1px solid rgba(99,235,165,0.3)",
            color: "rgba(99,235,165,0.9)" }}>
            Request a match with this agent
          </Link>
        </div>

        {/* Embed badge */}
        <div style={{ padding: "20px", borderRadius: 8, background: "rgba(255,255,255,0.02)",
          border: "1px solid rgba(255,255,255,0.06)", marginBottom: 24 }}>
          <div style={{ fontSize: 11, color: "rgba(255,255,255,0.25)", ...mono, letterSpacing: "0.08em", marginBottom: 12 }}>
            EMBED THIS BADGE
          </div>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={`${API_URL}/badge/${agent.id}`} alt={`${agent.name} AgentMesh badge`}
            style={{ marginBottom: 12, display: "block" }} />
          <div style={{ background: "rgba(0,0,0,0.3)", padding: "10px 12px", borderRadius: 6,
            border: "1px solid rgba(255,255,255,0.06)" }}>
            <code style={{ fontSize: 11, color: "rgba(255,255,255,0.5)", ...mono, wordBreak: "break-all" }}>
              {`![AgentMesh](https://agentmesh.io/badge/${agent.id})`}
            </code>
          </div>
        </div>

        {/* Copy link */}
        <div style={{ textAlign: "center", marginBottom: 20 }}>
          <div style={{ fontSize: 11, color: "rgba(255,255,255,0.2)", ...mono, marginBottom: 8 }}>
            SHARE THIS PROFILE
          </div>
          <div style={{ display: "inline-flex", alignItems: "center", gap: 8, padding: "8px 14px",
            borderRadius: 6, background: "rgba(255,255,255,0.03)", border: "1px solid rgba(255,255,255,0.08)" }}>
            <span style={{ fontSize: 12, color: "rgba(255,255,255,0.4)", ...mono }}>{profileUrl}</span>
          </div>
        </div>
      </div>
    </div>
  );
}
