"use client";
import { useState, useEffect, useCallback } from "react";
import Link from "next/link";
import { agentsApi, matchesApi, delegationsApi, type PublicAgent, type MatchEntry, type Delegation } from "@/lib/api";
import { ChainsTab, TeamsTab, BillingTab } from "@/components/AdvancedTabs";

// ─── Shared micro-components ─────────────────────────────────

const TOOL_COLORS: Record<string, string> = {
  gmail: "#EA4335", slack: "#4A154B", stripe: "#635BFF", notion: "#000",
  google_sheets: "#34A853", web_search: "#4285F4", yahoo_finance: "#720E9E",
  fred_api: "#B22222", twitter: "#000", github: "#24292F", postgres: "#336791",
  calendar: "#1967D2", anthropic: "#C96442", linear: "#5E6AD2",
};

function Pill({ id, dim }: { id: string; dim?: boolean }) {
  return (
    <span style={{
      display: "inline-flex", alignItems: "center", gap: 4, padding: "2px 8px",
      borderRadius: 4, fontSize: 11, fontFamily: "'IBM Plex Mono', monospace",
      background: dim ? "rgba(255,255,255,0.03)" : "rgba(255,255,255,0.07)",
      border: `1px solid ${dim ? "rgba(255,255,255,0.05)" : "rgba(255,255,255,0.1)"}`,
      color: dim ? "rgba(255,255,255,0.3)" : "rgba(255,255,255,0.7)",
    }}>
      <span style={{ width: 5, height: 5, borderRadius: "50%", flexShrink: 0,
        background: dim ? "rgba(255,255,255,0.15)" : (TOOL_COLORS[id] ?? "#888") }} />
      {id}
    </span>
  );
}

function StatusDot({ status }: { status: string }) {
  const c = { online: "#22c55e", idle: "#f59e0b", offline: "#555" }[status] ?? "#555";
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 5 }}>
      <span style={{ width: 6, height: 6, borderRadius: "50%", background: c, boxShadow: `0 0 5px ${c}` }} />
      <span style={{ fontSize: 11, color: "rgba(255,255,255,0.35)", fontFamily: "'IBM Plex Mono', monospace" }}>{status}</span>
    </span>
  );
}

const STATUS_STYLE: Record<string, { bg: string; border: string; color: string }> = {
  completed:  { bg: "rgba(34,197,94,0.1)",   border: "rgba(34,197,94,0.25)",   color: "#22c55e" },
  running:    { bg: "rgba(251,191,36,0.1)",   border: "rgba(251,191,36,0.25)",  color: "#fbbf24" },
  queued:     { bg: "rgba(99,100,235,0.1)",   border: "rgba(99,100,235,0.25)",  color: "#6364eb" },
  failed:     { bg: "rgba(239,68,68,0.1)",    border: "rgba(239,68,68,0.25)",   color: "#ef4444" },
  timed_out:  { bg: "rgba(156,163,175,0.1)",  border: "rgba(156,163,175,0.2)",  color: "#9ca3af" },
};

function Badge({ status }: { status: string }) {
  const st = STATUS_STYLE[status] ?? STATUS_STYLE.queued;
  return (
    <span style={{ padding: "2px 8px", borderRadius: 4, fontSize: 11, fontWeight: 600,
      fontFamily: "'IBM Plex Mono', monospace", background: st.bg, border: `1px solid ${st.border}`, color: st.color }}>
      {status}
    </span>
  );
}

// ─── Main Dashboard ───────────────────────────────────────────

type Tab = "agents" | "matches" | "delegations";

export default function Dashboard() {
  const [tab, setTab] = useState<Tab>("agents");
  const [apiKey, setApiKey] = useState("");
  const [keyInput, setKeyInput] = useState("");
  const [agents, setAgents]       = useState<PublicAgent[]>([]);
  const [matches, setMatches]     = useState<MatchEntry[]>([]);
  const [delegations, setDelegations] = useState<Delegation[]>([]);
  const [loading, setLoading]     = useState(false);
  const [selected, setSelected]   = useState<PublicAgent | null>(null);
  const [expandedDl, setExpandedDl] = useState<string | null>(null);
  const [toast, setToast]         = useState("");

  const showToast = (msg: string) => { setToast(msg); setTimeout(() => setToast(""), 3000); };

  const loadAgents = useCallback(async () => {
    setLoading(true);
    try { setAgents((await agentsApi.list({ limit: 50 })).agents); }
    catch { showToast("Failed to load agents"); }
    finally { setLoading(false); }
  }, []);

  const loadMatches = useCallback(async () => {
    if (!apiKey) return;
    setLoading(true);
    try { setMatches((await matchesApi.list(apiKey, { limit: 50 })).matches); }
    catch { showToast("Failed to load matches"); }
    finally { setLoading(false); }
  }, [apiKey]);

  const loadDelegations = useCallback(async () => {
    if (!apiKey) return;
    setLoading(true);
    try { setDelegations((await delegationsApi.list(apiKey, { limit: 100 })).delegations); }
    catch { showToast("Failed to load delegations"); }
    finally { setLoading(false); }
  }, [apiKey]);

  useEffect(() => { loadAgents(); }, [loadAgents]);
  useEffect(() => { if (tab === "matches") loadMatches(); }, [tab, loadMatches]);
  useEffect(() => { if (tab === "delegations") loadDelegations(); }, [tab, loadDelegations]);

  const handleApprove = async (matchId: string) => {
    if (!apiKey) return showToast("Enter your API key first");
    try {
      await matchesApi.approve(matchId, apiKey);
      showToast("Match approved!");
      loadMatches();
    } catch (e: unknown) { showToast(e instanceof Error ? e.message : "Failed"); }
  };

  const handleDismiss = async (matchId: string) => {
    if (!apiKey) return showToast("Enter your API key first");
    try {
      await matchesApi.dismiss(matchId, apiKey);
      loadMatches();
    } catch (e: unknown) { showToast(e instanceof Error ? e.message : "Failed"); }
  };

  const TABS: Tab[] = ["agents", "matches", "delegations"];
  const pendingMatches = matches.filter(m => m.status === "pending").length;

  return (
    <div style={{ minHeight: "100vh", background: "#0a0a0f",
      backgroundImage: "radial-gradient(ellipse 80% 40% at 50% -10%, rgba(99,235,165,0.05) 0%, transparent 60%)" }}>

      {/* Toast */}
      {toast && (
        <div style={{ position: "fixed", bottom: 24, right: 24, zIndex: 100,
          padding: "12px 18px", borderRadius: 8, background: "rgba(20,20,30,0.95)",
          border: "1px solid rgba(255,255,255,0.12)", fontSize: 13, color: "#fff",
          boxShadow: "0 8px 24px rgba(0,0,0,0.4)" }}>
          {toast}
        </div>
      )}

      {/* Header */}
      <div style={{ padding: "18px 32px", borderBottom: "1px solid rgba(255,255,255,0.06)",
        display: "flex", alignItems: "center", justifyContent: "space-between",
        position: "sticky", top: 0, zIndex: 10, backdropFilter: "blur(12px)",
        background: "rgba(10,10,15,0.85)" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/" style={{ display: "flex", alignItems: "center", gap: 10 }}>
            <div style={{ width: 26, height: 26, borderRadius: 6, fontSize: 13,
              background: "linear-gradient(135deg, rgba(99,235,165,0.8), rgba(99,100,235,0.6))",
              display: "flex", alignItems: "center", justifyContent: "center" }}>⬡</div>
            <span style={{ fontSize: 15, fontWeight: 700, letterSpacing: "-0.02em" }}>AgentMesh</span>
          </Link>
          <span style={{ fontSize: 10, padding: "2px 6px", borderRadius: 20,
            background: "rgba(99,235,165,0.1)", border: "1px solid rgba(99,235,165,0.2)",
            color: "rgba(99,235,165,0.6)", fontFamily: "'IBM Plex Mono', monospace" }}>BETA</span>
        </div>

        <div style={{ display: "flex", gap: 3 }}>
          {TABS.map(t => (
            <button key={t} onClick={() => setTab(t)} style={{
              padding: "6px 14px", borderRadius: 6, fontSize: 13, fontWeight: 500,
              background: tab === t ? "rgba(255,255,255,0.08)" : "transparent",
              border: `1px solid ${tab === t ? "rgba(255,255,255,0.1)" : "transparent"}`,
              color: tab === t ? "#fff" : "rgba(255,255,255,0.4)", textTransform: "capitalize",
            }}>
              {t}
              {t === "matches" && pendingMatches > 0 && (
                <span style={{ marginLeft: 5, fontSize: 10, padding: "1px 5px", borderRadius: 10,
                  background: "rgba(99,235,165,0.2)", color: "rgba(99,235,165,0.9)",
                  fontFamily: "'IBM Plex Mono', monospace" }}>{pendingMatches}</span>
              )}
            </button>
          ))}
        </div>

        <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
          <input
            placeholder="Paste API key..."
            value={keyInput}
            onChange={e => setKeyInput(e.target.value)}
            onKeyDown={e => e.key === "Enter" && setApiKey(keyInput)}
            style={{ padding: "7px 12px", borderRadius: 6, fontSize: 12,
              background: "rgba(255,255,255,0.04)", border: `1px solid ${apiKey ? "rgba(99,235,165,0.25)" : "rgba(255,255,255,0.08)"}`,
              color: "#fff", outline: "none", width: 200, fontFamily: "'IBM Plex Mono', monospace" }}
          />
          {apiKey
            ? <span style={{ fontSize: 11, color: "rgba(99,235,165,0.6)", fontFamily: "'IBM Plex Mono', monospace" }}>● authed</span>
            : <button onClick={() => setApiKey(keyInput)} style={{ padding: "7px 12px", borderRadius: 6, fontSize: 12,
                background: "rgba(255,255,255,0.05)", border: "1px solid rgba(255,255,255,0.1)",
                color: "rgba(255,255,255,0.5)" }}>Set key</button>
          }
          <Link href="/register" style={{ padding: "7px 14px", borderRadius: 6, fontSize: 13, fontWeight: 600,
            background: "rgba(99,235,165,0.1)", border: "1px solid rgba(99,235,165,0.25)",
            color: "rgba(99,235,165,0.85)" }}>+ Register</Link>
        </div>
      </div>

      <div style={{ padding: "28px 32px", maxWidth: 1100, margin: "0 auto" }}>

        {/* ── AGENTS ── */}
        {tab === "agents" && (
          <div>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end", marginBottom: 20 }}>
              <div>
                <h1 style={{ fontSize: 20, fontWeight: 700, letterSpacing: "-0.03em", margin: "0 0 3px" }}>Agent Registry</h1>
                <p style={{ fontSize: 13, color: "rgba(255,255,255,0.35)", margin: 0 }}>
                  {agents.length} agents · {agents.filter(a => a.status === "online").length} online
                </p>
              </div>
              <button onClick={loadAgents} style={{ padding: "7px 14px", borderRadius: 6, fontSize: 12,
                background: "rgba(255,255,255,0.04)", border: "1px solid rgba(255,255,255,0.08)",
                color: "rgba(255,255,255,0.4)" }}>↻ Refresh</button>
            </div>

            {loading && <div style={{ color: "rgba(255,255,255,0.3)", fontSize: 13 }}>Loading...</div>}

            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
              {agents.map(agent => (
                <div key={agent.id} onClick={() => setSelected(selected?.id === agent.id ? null : agent)}
                  style={{ padding: "16px 18px", borderRadius: 8, cursor: "pointer",
                    background: selected?.id === agent.id ? "rgba(99,235,165,0.05)" : "rgba(255,255,255,0.02)",
                    border: `1px solid ${selected?.id === agent.id ? "rgba(99,235,165,0.2)" : "rgba(255,255,255,0.06)"}`,
                    transition: "all 0.12s" }}>
                  <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 8 }}>
                    <div>
                      <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 2 }}>{agent.name}</div>
                      <div style={{ fontSize: 11, color: "rgba(255,255,255,0.3)", fontFamily: "'IBM Plex Mono', monospace" }}>
                        {agent.id} · {agent.framework}
                      </div>
                    </div>
                    <StatusDot status={agent.status} />
                  </div>
                  <div style={{ fontSize: 12, color: "rgba(255,255,255,0.4)", marginBottom: 10, lineHeight: 1.5 }}>
                    {agent.description}
                  </div>
                  <div style={{ display: "flex", flexWrap: "wrap", gap: 4, marginBottom: 6 }}>
                    {agent.has.map(t => <Pill key={t} id={t} />)}
                  </div>
                  {agent.needs.length > 0 && (
                    <div style={{ display: "flex", flexWrap: "wrap", gap: 4, alignItems: "center" }}>
                      <span style={{ fontSize: 10, color: "rgba(255,255,255,0.2)", fontFamily: "'IBM Plex Mono', monospace" }}>needs:</span>
                      {agent.needs.map(t => <Pill key={t} id={t} dim />)}
                    </div>
                  )}
                  <div style={{ display: "flex", gap: 16, marginTop: 10, paddingTop: 10,
                    borderTop: "1px solid rgba(255,255,255,0.04)" }}>
                    <span style={{ fontSize: 11, color: "rgba(255,255,255,0.25)", fontFamily: "'IBM Plex Mono', monospace" }}>
                      <span style={{ color: "rgba(99,235,165,0.7)" }}>{agent.stats.delegations_sent}</span> sent
                    </span>
                    <span style={{ fontSize: 11, color: "rgba(255,255,255,0.25)", fontFamily: "'IBM Plex Mono', monospace" }}>
                      <span style={{ color: "rgba(255,255,255,0.5)" }}>{agent.stats.delegations_received}</span> received
                    </span>
                    <span style={{ fontSize: 11, color: "rgba(255,255,255,0.25)", fontFamily: "'IBM Plex Mono', monospace" }}>
                      <span style={{ color: "#22c55e" }}>{agent.stats.success_count}</span> / {agent.stats.success_count + agent.stats.fail_count} success
                    </span>
                  </div>
                </div>
              ))}
            </div>

            {selected && (
              <div style={{ marginTop: 16, padding: "18px 20px", borderRadius: 8,
                background: "rgba(99,235,165,0.03)", border: "1px solid rgba(99,235,165,0.12)" }}>
                <div style={{ fontSize: 11, color: "rgba(99,235,165,0.5)", fontFamily: "'IBM Plex Mono', monospace", marginBottom: 10 }}>
                  CAPABILITY MANIFEST · {selected.name}
                </div>
                <pre style={{ fontSize: 12, color: "rgba(255,255,255,0.55)", fontFamily: "'IBM Plex Mono', monospace",
                  margin: 0, lineHeight: 1.7 }}>
                  {JSON.stringify({ agent_id: selected.id, framework: selected.framework,
                    has: selected.has, needs: selected.needs, status: selected.status,
                    endpoint: `${process.env.NEXT_PUBLIC_API_URL}/delegate` }, null, 2)}
                </pre>
              </div>
            )}
          </div>
        )}

        {/* ── MATCHES ── */}
        {tab === "matches" && (
          <div>
            <div style={{ marginBottom: 20 }}>
              <h1 style={{ fontSize: 20, fontWeight: 700, letterSpacing: "-0.03em", margin: "0 0 3px" }}>Matches</h1>
              <p style={{ fontSize: 13, color: "rgba(255,255,255,0.35)", margin: 0 }}>
                Agent pairs scored by tool complementarity
              </p>
            </div>
            {!apiKey && (
              <div style={{ padding: "14px 16px", borderRadius: 8, background: "rgba(251,191,36,0.05)",
                border: "1px solid rgba(251,191,36,0.15)", fontSize: 13, color: "rgba(251,191,36,0.7)", marginBottom: 20 }}>
                ⚠ Enter your API key in the header to see your agent's matches
              </div>
            )}
            {loading && <div style={{ color: "rgba(255,255,255,0.3)", fontSize: 13 }}>Loading...</div>}
            {matches.length === 0 && !loading && apiKey && (
              <div style={{ color: "rgba(255,255,255,0.3)", fontSize: 13 }}>No matches found. Register an agent with declared needs to get matches.</div>
            )}
            {matches.map(m => (
              <div key={m.match_id} style={{ padding: "18px", borderRadius: 8, marginBottom: 10,
                background: m.status === "pending" ? "rgba(99,235,165,0.03)" : "rgba(255,255,255,0.02)",
                border: `1px solid ${m.status === "pending" ? "rgba(99,235,165,0.15)" : "rgba(255,255,255,0.06)"}` }}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 10 }}>
                  <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                    <span style={{ fontSize: 14, fontWeight: 600 }}>Your agent</span>
                    <span style={{ color: "rgba(99,235,165,0.5)", fontFamily: "'IBM Plex Mono', monospace" }}>⇄</span>
                    <span style={{ fontSize: 14, fontWeight: 600 }}>{m.agent?.name}</span>
                    <span style={{ fontSize: 11, color: "rgba(255,255,255,0.3)", fontFamily: "'IBM Plex Mono', monospace" }}>
                      by {m.agent?.owner}
                    </span>
                  </div>
                  <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                    <Badge status={m.status} />
                    <span style={{ padding: "3px 10px", borderRadius: 20, fontSize: 12, fontWeight: 700,
                      fontFamily: "'IBM Plex Mono', monospace",
                      background: m.score > 90 ? "rgba(99,235,165,0.12)" : "rgba(255,255,255,0.06)",
                      border: `1px solid ${m.score > 90 ? "rgba(99,235,165,0.25)" : "rgba(255,255,255,0.1)"}`,
                      color: m.score > 90 ? "rgba(99,235,165,0.9)" : "rgba(255,255,255,0.5)" }}>
                      {m.score}%
                    </span>
                  </div>
                </div>
                <div style={{ fontSize: 12, color: "rgba(255,255,255,0.4)", marginBottom: 10 }}>{m.reason}</div>
                <div style={{ display: "flex", flexWrap: "wrap", gap: 4, marginBottom: 12 }}>
                  {m.covering_needs.map(t => <Pill key={t} id={t} />)}
                </div>
                <div style={{ display: "flex", gap: 6, fontSize: 11, color: "rgba(255,255,255,0.25)",
                  fontFamily: "'IBM Plex Mono', monospace", marginBottom: m.status === "pending" ? 12 : 0 }}>
                  <span>overlap: {m.score_breakdown.tool_overlap}pts</span>
                  <span>·</span>
                  <span>domain: {m.score_breakdown.domain_proximity}pts</span>
                  <span>·</span>
                  <span>reliability: {m.score_breakdown.reliability}pts</span>
                </div>
                {m.status === "pending" && (
                  <div style={{ display: "flex", gap: 8 }}>
                    <button onClick={() => handleApprove(m.match_id)} style={{
                      padding: "7px 14px", borderRadius: 5, fontSize: 12, fontWeight: 600,
                      background: "rgba(99,235,165,0.12)", border: "1px solid rgba(99,235,165,0.25)",
                      color: "rgba(99,235,165,0.9)" }}>Approve match</button>
                    <button onClick={() => handleDismiss(m.match_id)} style={{
                      padding: "7px 14px", borderRadius: 5, fontSize: 12,
                      background: "transparent", border: "1px solid rgba(255,255,255,0.08)",
                      color: "rgba(255,255,255,0.35)" }}>Dismiss</button>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}

        {/* ── DELEGATIONS ── */}
        {tab === "delegations" && (
          <div>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end", marginBottom: 20 }}>
              <div>
                <h1 style={{ fontSize: 20, fontWeight: 700, letterSpacing: "-0.03em", margin: "0 0 3px" }}>Delegation Log</h1>
                <p style={{ fontSize: 13, color: "rgba(255,255,255,0.35)", margin: 0 }}>Live task handoffs between agents</p>
              </div>
              <div style={{ display: "flex", gap: 20 }}>
                {[
                  { label: "Total",     val: delegations.length,                                             color: "rgba(255,255,255,0.6)" },
                  { label: "Completed", val: delegations.filter(d => d.status === "completed").length,       color: "#22c55e" },
                  { label: "Running",   val: delegations.filter(d => d.status === "running").length,         color: "#fbbf24" },
                  { label: "Failed",    val: delegations.filter(d => d.status === "failed").length,          color: "#ef4444" },
                ].map(s => (
                  <div key={s.label} style={{ textAlign: "center" }}>
                    <div style={{ fontSize: 20, fontWeight: 700, color: s.color, fontFamily: "'IBM Plex Mono', monospace", lineHeight: 1 }}>{s.val}</div>
                    <div style={{ fontSize: 10, color: "rgba(255,255,255,0.3)", marginTop: 2 }}>{s.label}</div>
                  </div>
                ))}
              </div>
            </div>

            {!apiKey && (
              <div style={{ padding: "14px 16px", borderRadius: 8, background: "rgba(251,191,36,0.05)",
                border: "1px solid rgba(251,191,36,0.15)", fontSize: 13, color: "rgba(251,191,36,0.7)", marginBottom: 20 }}>
                ⚠ Enter your API key to see your delegation history
              </div>
            )}

            <div style={{ borderRadius: 8, border: "1px solid rgba(255,255,255,0.06)", overflow: "hidden",
              background: "rgba(255,255,255,0.01)" }}>
              {/* Header row */}
              <div style={{ display: "grid", gridTemplateColumns: "1fr 120px 1fr 90px 70px 24px",
                gap: 12, padding: "10px 16px", background: "rgba(255,255,255,0.03)",
                borderBottom: "1px solid rgba(255,255,255,0.06)" }}>
                {["Route", "Task", "Input", "Status", "Duration", ""].map((h, i) => (
                  <span key={i} style={{ fontSize: 10, color: "rgba(255,255,255,0.2)",
                    fontFamily: "'IBM Plex Mono', monospace", letterSpacing: "0.07em" }}>{h}</span>
                ))}
              </div>

              {loading && (
                <div style={{ padding: "20px 16px", color: "rgba(255,255,255,0.3)", fontSize: 13 }}>Loading...</div>
              )}

              {delegations.length === 0 && !loading && (
                <div style={{ padding: "20px 16px", color: "rgba(255,255,255,0.25)", fontSize: 13 }}>
                  {apiKey ? "No delegations yet." : "Enter API key to load delegation history."}
                </div>
              )}

              {delegations.map(d => (
                <div key={d.id} style={{ borderBottom: "1px solid rgba(255,255,255,0.03)" }}>
                  <div onClick={() => setExpandedDl(expandedDl === d.id ? null : d.id)}
                    style={{ display: "grid", gridTemplateColumns: "1fr 120px 1fr 90px 70px 24px",
                      gap: 12, padding: "11px 16px", alignItems: "center", cursor: "pointer",
                      background: expandedDl === d.id ? "rgba(255,255,255,0.02)" : "transparent" }}>
                    <span style={{ fontSize: 12, color: "rgba(255,255,255,0.6)", fontFamily: "'IBM Plex Mono', monospace",
                      overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                      {d.from_agent_id.slice(0, 12)} <span style={{ color: "rgba(255,255,255,0.2)" }}>→</span> {d.to_agent_id.slice(0, 12)}
                    </span>
                    <span style={{ fontSize: 12, color: "rgba(255,255,255,0.4)", fontFamily: "'IBM Plex Mono', monospace" }}>{d.task}</span>
                    <span style={{ fontSize: 12, color: "rgba(255,255,255,0.3)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                      {JSON.stringify(d.input).slice(0, 50)}
                    </span>
                    <Badge status={d.status} />
                    <span style={{ fontSize: 11, color: "rgba(255,255,255,0.2)", fontFamily: "'IBM Plex Mono', monospace", textAlign: "right" }}>
                      {d.duration_ms ? `${d.duration_ms}ms` : "—"}
                    </span>
                    <span style={{ color: "rgba(255,255,255,0.2)", fontSize: 10, textAlign: "center" }}>
                      {expandedDl === d.id ? "▲" : "▼"}
                    </span>
                  </div>
                  {expandedDl === d.id && (
                    <div style={{ padding: "12px 16px 16px", background: "rgba(0,0,0,0.2)",
                      borderTop: "1px solid rgba(255,255,255,0.04)" }}>
                      <div style={{ fontSize: 10, color: "rgba(255,255,255,0.2)", fontFamily: "'IBM Plex Mono', monospace", marginBottom: 8 }}>
                        {d.status === "completed" ? "RESPONSE PAYLOAD" : d.status === "failed" ? "ERROR PAYLOAD" : "INPUT"}
                      </div>
                      <pre style={{ fontSize: 12, color: d.status === "completed" ? "rgba(99,235,165,0.7)" : "rgba(239,68,68,0.7)",
                        fontFamily: "'IBM Plex Mono', monospace", margin: 0,
                        background: "rgba(0,0,0,0.25)", padding: "10px 12px", borderRadius: 6,
                        border: "1px solid rgba(255,255,255,0.05)", overflowX: "auto" }}>
                        {JSON.stringify(d.output ?? d.error ?? d.input, null, 2)}
                      </pre>
                      <div style={{ marginTop: 8, fontSize: 11, color: "rgba(255,255,255,0.2)", fontFamily: "'IBM Plex Mono', monospace" }}>
                        id: {d.id} · created: {new Date(d.created_at).toLocaleTimeString()}
                        {d.completed_at && ` · completed: ${new Date(d.completed_at).toLocaleTimeString()}`}
                      </div>
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
