"use client";
import { useState, useEffect, useCallback } from "react";
import { chainsApi, teamsApi, billingApi, type DelegationChain, type Team, type BillingTransaction, type BillingRate } from "@/lib/api";

// ─── Shared styles ────────────────────────────────────────────
const mono = { fontFamily: "'IBM Plex Mono', monospace" } as const;
const s = {
  card: { padding: "16px 18px", borderRadius: 8, background: "rgba(255,255,255,0.02)", border: "1px solid rgba(255,255,255,0.06)", marginBottom: 10 } as React.CSSProperties,
  label: { fontSize: 10, color: "rgba(255,255,255,0.25)", ...mono, letterSpacing: "0.07em", marginBottom: 6, display: "block" } as React.CSSProperties,
  val: { fontSize: 13, color: "rgba(255,255,255,0.7)" } as React.CSSProperties,
};

function Stat({ label, value, color }: { label: string; value: string | number; color?: string }) {
  return (
    <div style={{ textAlign: "center" }}>
      <div style={{ fontSize: 22, fontWeight: 700, color: color ?? "rgba(255,255,255,0.6)", ...mono, lineHeight: 1 }}>{value}</div>
      <div style={{ fontSize: 10, color: "rgba(255,255,255,0.3)", marginTop: 2 }}>{label}</div>
    </div>
  );
}

// ─── STATUS COLORS ────────────────────────────────────────────
function StatusBadge({ status }: { status: string }) {
  const map: Record<string, [string, string]> = {
    completed:  ["rgba(34,197,94,0.1)",  "#22c55e"],
    running:    ["rgba(251,191,36,0.1)", "#fbbf24"],
    failed:     ["rgba(239,68,68,0.1)",  "#ef4444"],
    pending:    ["rgba(99,100,235,0.1)", "#6364eb"],
    active:     ["rgba(99,235,165,0.1)", "#63eba5"],
  };
  const [bg, color] = map[status] ?? ["rgba(255,255,255,0.05)", "rgba(255,255,255,0.4)"];
  return (
    <span style={{ padding: "2px 8px", borderRadius: 4, fontSize: 11, fontWeight: 600, ...mono, background: bg, color }}>
      {status}
    </span>
  );
}

// ═══════════════════════════════════════════════════════
// CHAINS TAB (v0.2)
// ═══════════════════════════════════════════════════════
export function ChainsTab({ apiKey }: { apiKey: string }) {
  const [chains, setChains] = useState<DelegationChain[]>([]);
  const [loading, setLoading] = useState(false);
  const [expanded, setExpanded] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!apiKey) return;
    setLoading(true);
    try { setChains((await chainsApi.list(apiKey)).chains); }
    catch { /* silent */ }
    finally { setLoading(false); }
  }, [apiKey]);

  useEffect(() => { load(); }, [load]);

  return (
    <div>
      <div style={{ marginBottom: 20, display: "flex", justifyContent: "space-between", alignItems: "flex-end" }}>
        <div>
          <h1 style={{ fontSize: 20, fontWeight: 700, letterSpacing: "-0.03em", margin: "0 0 3px" }}>Delegation Chains</h1>
          <p style={{ fontSize: 13, color: "rgba(255,255,255,0.35)", margin: 0 }}>Multi-hop task flows across agent boundaries</p>
        </div>
        <div style={{ display: "flex", gap: 20 }}>
          <Stat label="Total" value={chains.length} />
          <Stat label="Completed" value={chains.filter(c => c.status === "completed").length} color="#22c55e" />
          <Stat label="Running"   value={chains.filter(c => c.status === "running").length}   color="#fbbf24" />
          <Stat label="Failed"    value={chains.filter(c => c.status === "failed").length}    color="#ef4444" />
        </div>
      </div>

      {!apiKey && <AuthWarning />}
      {loading && <LoadingRow />}

      {/* Explainer */}
      <div style={{ padding: "14px 16px", borderRadius: 8, background: "rgba(99,235,165,0.03)",
        border: "1px solid rgba(99,235,165,0.1)", marginBottom: 20, fontSize: 12, color: "rgba(255,255,255,0.4)" }}>
        <span style={{ color: "rgba(99,235,165,0.7)", fontWeight: 600 }}>How chains work: </span>
        When delegating, pass <code style={{ ...mono, background: "rgba(255,255,255,0.06)", padding: "1px 5px", borderRadius: 3 }}>chain_id</code> and <code style={{ ...mono, background: "rgba(255,255,255,0.06)", padding: "1px 5px", borderRadius: 3 }}>chain_depth</code> to link hops. Agent B can then continue the chain by delegating to Agent C with depth+1.
        Maximum depth: 5 hops.
      </div>

      {chains.map(chain => (
        <div key={chain.chain_id} style={{ ...s.card, border: `1px solid ${chain.status === "completed" ? "rgba(34,197,94,0.15)" : chain.status === "failed" ? "rgba(239,68,68,0.12)" : "rgba(255,255,255,0.06)"}` }}>
          <div onClick={() => setExpanded(expanded === chain.chain_id ? null : chain.chain_id)}
            style={{ display: "flex", justifyContent: "space-between", alignItems: "center", cursor: "pointer", marginBottom: expanded === chain.chain_id ? 16 : 0 }}>
            <div>
              <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 4 }}>
                <StatusBadge status={chain.status} />
                <span style={{ fontSize: 12, color: "rgba(255,255,255,0.25)", ...mono }}>{chain.chain_id}</span>
              </div>
              <div style={{ fontSize: 13, color: "rgba(255,255,255,0.6)" }}>
                {chain.hops.map(h => h.to_agent).join(" → ")}
              </div>
            </div>
            <div style={{ display: "flex", gap: 20, alignItems: "center" }}>
              <Stat label="Hops" value={chain.hops.length} />
              <Stat label="Duration" value={chain.total_duration_ms > 0 ? `${chain.total_duration_ms}ms` : "—"} />
              <span style={{ color: "rgba(255,255,255,0.2)", fontSize: 10 }}>{expanded === chain.chain_id ? "▲" : "▼"}</span>
            </div>
          </div>

          {expanded === chain.chain_id && (
            <div>
              {/* Chain visualization */}
              <div style={{ display: "flex", alignItems: "center", gap: 0, marginBottom: 16, overflowX: "auto" }}>
                {chain.hops.map((hop, i) => (
                  <div key={hop.delegation_id} style={{ display: "flex", alignItems: "center" }}>
                    <div style={{ padding: "10px 14px", borderRadius: 8, minWidth: 130,
                      background: hop.status === "completed" ? "rgba(34,197,94,0.08)" : hop.status === "failed" ? "rgba(239,68,68,0.08)" : "rgba(255,255,255,0.04)",
                      border: `1px solid ${hop.status === "completed" ? "rgba(34,197,94,0.2)" : hop.status === "failed" ? "rgba(239,68,68,0.2)" : "rgba(255,255,255,0.08)"}` }}>
                      <div style={{ fontSize: 10, color: "rgba(255,255,255,0.3)", ...mono, marginBottom: 3 }}>Hop {hop.depth}</div>
                      <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 2 }}>{hop.to_agent}</div>
                      <div style={{ fontSize: 11, color: "rgba(255,255,255,0.4)", ...mono, marginBottom: 4 }}>{hop.task}</div>
                      <StatusBadge status={hop.status} />
                      {hop.duration_ms && <div style={{ fontSize: 10, color: "rgba(255,255,255,0.25)", ...mono, marginTop: 4 }}>{hop.duration_ms}ms</div>}
                    </div>
                    {i < chain.hops.length - 1 && (
                      <div style={{ padding: "0 8px", color: "rgba(99,235,165,0.4)", fontSize: 16 }}>→</div>
                    )}
                  </div>
                ))}
              </div>

              {/* Hop detail table */}
              <div style={{ borderRadius: 6, border: "1px solid rgba(255,255,255,0.06)", overflow: "hidden" }}>
                {chain.hops.map(hop => (
                  <div key={hop.delegation_id} style={{ display: "grid", gridTemplateColumns: "40px 1fr 1fr 120px 70px",
                    gap: 12, padding: "10px 14px", borderBottom: "1px solid rgba(255,255,255,0.04)",
                    fontSize: 12, alignItems: "center" }}>
                    <span style={{ color: "rgba(255,255,255,0.3)", ...mono }}>#{hop.depth}</span>
                    <span style={{ color: "rgba(255,255,255,0.6)", ...mono }}>{hop.from_agent} → {hop.to_agent}</span>
                    <span style={{ color: "rgba(255,255,255,0.4)", ...mono }}>{hop.task}</span>
                    <StatusBadge status={hop.status} />
                    <span style={{ color: "rgba(255,255,255,0.25)", ...mono, textAlign: "right" }}>
                      {hop.duration_ms ? `${hop.duration_ms}ms` : "—"}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      ))}

      {chains.length === 0 && !loading && apiKey && (
        <EmptyState message="No chains yet. Pass chain_id and chain_depth when delegating to create multi-hop flows." />
      )}
    </div>
  );
}

// ═══════════════════════════════════════════════════════
// TEAMS TAB (v0.3)
// ═══════════════════════════════════════════════════════
export function TeamsTab({ apiKey }: { apiKey: string }) {
  const [teams, setTeams] = useState<Team[]>([]);
  const [loading, setLoading] = useState(false);
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState({ name: "", description: "", owner_email: "", public: true });
  const [creating, setCreating] = useState(false);
  const [newTeamKey, setNewTeamKey] = useState<{ team_id: string; api_key: string } | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    teamsApi.list().then(d => setTeams(d.teams)).catch(() => {}).finally(() => setLoading(false));
  }, []);

  const createTeam = async () => {
    setCreating(true);
    try {
      const res = await teamsApi.create(form);
      setNewTeamKey(res);
      setShowCreate(false);
      const updated = await teamsApi.list();
      setTeams(updated.teams);
    } catch { /* silent */ }
    finally { setCreating(false); }
  };

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end", marginBottom: 20 }}>
        <div>
          <h1 style={{ fontSize: 20, fontWeight: 700, letterSpacing: "-0.03em", margin: "0 0 3px" }}>Agent Teams</h1>
          <p style={{ fontSize: 13, color: "rgba(255,255,255,0.35)", margin: 0 }}>
            Named groups of agents that act as a single collaborator
          </p>
        </div>
        <button onClick={() => setShowCreate(!showCreate)} style={{
          padding: "7px 14px", borderRadius: 6, fontSize: 13, fontWeight: 600,
          background: "rgba(99,235,165,0.1)", border: "1px solid rgba(99,235,165,0.25)",
          color: "rgba(99,235,165,0.85)" }}>
          {showCreate ? "Cancel" : "+ Create team"}
        </button>
      </div>

      {newTeamKey && (
        <div style={{ padding: "16px 18px", borderRadius: 8, background: "rgba(99,235,165,0.05)",
          border: "1px solid rgba(99,235,165,0.2)", marginBottom: 16 }}>
          <div style={{ fontSize: 12, color: "rgba(99,235,165,0.7)", marginBottom: 8 }}>Team created — save your API key</div>
          <div style={{ fontSize: 11, ...mono, color: "rgba(255,255,255,0.5)", wordBreak: "break-all",
            background: "rgba(0,0,0,0.2)", padding: "8px 10px", borderRadius: 5 }}>
            {newTeamKey.api_key}
          </div>
          <button onClick={() => { navigator.clipboard.writeText(newTeamKey.api_key); setNewTeamKey(null); }}
            style={{ marginTop: 8, padding: "5px 12px", borderRadius: 5, fontSize: 12,
              background: "rgba(99,235,165,0.1)", border: "1px solid rgba(99,235,165,0.2)",
              color: "rgba(99,235,165,0.8)" }}>
            Copy & dismiss
          </button>
        </div>
      )}

      {showCreate && (
        <div style={{ padding: "20px", borderRadius: 8, background: "rgba(255,255,255,0.02)",
          border: "1px solid rgba(255,255,255,0.1)", marginBottom: 16 }}>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12, marginBottom: 12 }}>
            {[["name", "Team name", "text"], ["owner_email", "Owner email", "email"]].map(([k, ph, type]) => (
              <div key={k}>
                <label style={s.label}>{ph.toUpperCase()}</label>
                <input value={(form as Record<string, string>)[k]} onChange={e => setForm(f => ({ ...f, [k]: e.target.value }))}
                  type={type} placeholder={ph}
                  style={{ width: "100%", padding: "8px 10px", borderRadius: 5, fontSize: 13,
                    background: "rgba(255,255,255,0.04)", border: "1px solid rgba(255,255,255,0.1)",
                    color: "#fff", outline: "none" }} />
              </div>
            ))}
          </div>
          <div style={{ marginBottom: 12 }}>
            <label style={s.label}>DESCRIPTION</label>
            <textarea value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
              placeholder="What does this team do?"
              style={{ width: "100%", padding: "8px 10px", borderRadius: 5, fontSize: 13, height: 64, resize: "none",
                background: "rgba(255,255,255,0.04)", border: "1px solid rgba(255,255,255,0.1)",
                color: "#fff", outline: "none" }} />
          </div>
          <button onClick={createTeam} disabled={creating || !form.name || !form.owner_email}
            style={{ padding: "9px 20px", borderRadius: 6, fontSize: 13, fontWeight: 600,
              background: "rgba(99,235,165,0.12)", border: "1px solid rgba(99,235,165,0.25)",
              color: "rgba(99,235,165,0.9)", opacity: creating ? 0.6 : 1 }}>
            {creating ? "Creating..." : "Create team"}
          </button>
        </div>
      )}

      {loading && <LoadingRow />}

      {teams.map(team => (
        <div key={team.id} style={s.card}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}
            onClick={() => setExpanded(expanded === team.id ? null : team.id)}>
            <div style={{ cursor: "pointer" }}>
              <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 4 }}>{team.name}</div>
              <div style={{ fontSize: 12, color: "rgba(255,255,255,0.4)", marginBottom: 8 }}>{team.description}</div>
              <div style={{ display: "flex", flexWrap: "wrap", gap: 4 }}>
                {team.has.map(t => (
                  <span key={t} style={{ fontSize: 11, padding: "2px 7px", borderRadius: 4, ...mono,
                    background: "rgba(99,235,165,0.08)", border: "1px solid rgba(99,235,165,0.15)",
                    color: "rgba(99,235,165,0.7)" }}>{t}</span>
                ))}
              </div>
            </div>
            <div style={{ display: "flex", gap: 16, alignItems: "center" }}>
              <Stat label="Members" value={team.members.length} />
              <Stat label="Sent" value={team.stats.delegations_sent} />
              <span style={{ color: "rgba(255,255,255,0.2)", fontSize: 10, cursor: "pointer" }}>
                {expanded === team.id ? "▲" : "▼"}
              </span>
            </div>
          </div>

          {expanded === team.id && team.members.length > 0 && (
            <div style={{ marginTop: 14, paddingTop: 14, borderTop: "1px solid rgba(255,255,255,0.05)" }}>
              <div style={s.label}>MEMBERS</div>
              <div style={{ display: "grid", gridTemplateColumns: "1fr 80px 120px", gap: 8 }}>
                {team.members.map(m => (
                  <div key={m.agent_id} style={{ display: "contents", fontSize: 12 }}>
                    <span style={{ color: "rgba(255,255,255,0.7)", ...mono }}>{m.name}</span>
                    <span style={{ padding: "2px 6px", borderRadius: 4, fontSize: 10, ...mono,
                      background: m.role === "lead" ? "rgba(99,235,165,0.1)" : "rgba(255,255,255,0.05)",
                      color: m.role === "lead" ? "rgba(99,235,165,0.8)" : "rgba(255,255,255,0.4)",
                      alignSelf: "center" }}>{m.role}</span>
                    <span style={{ color: "rgba(255,255,255,0.25)", fontSize: 11, ...mono }}>
                      {new Date(m.added_at).toLocaleDateString()}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      ))}

      {teams.length === 0 && !loading && (
        <EmptyState message="No teams yet. Create a team to group agents and act as a unified collaborator." />
      )}
    </div>
  );
}

// ═══════════════════════════════════════════════════════
// BILLING TAB (v0.3)
// ═══════════════════════════════════════════════════════
export function BillingTab({ apiKey }: { apiKey: string }) {
  const [rates, setRates]   = useState<BillingRate[]>([]);
  const [txns, setTxns]     = useState<BillingTransaction[]>([]);
  const [summary, setSummary] = useState({ earned: 0, spent: 0 });
  const [loading, setLoading] = useState(false);
  const [newTask, setNewTask] = useState("");
  const [newPrice, setNewPrice] = useState("");
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    if (!apiKey) return;
    setLoading(true);
    try {
      const [rateData, txnData] = await Promise.all([
        billingApi.getRates(apiKey),
        billingApi.getTransactions(apiKey),
      ]);
      setRates(rateData.rates);
      setTxns(txnData.transactions);
      setSummary({ earned: txnData.total_earned_cents, spent: txnData.total_spent_cents });
    } catch { /* silent */ }
    finally { setLoading(false); }
  }, [apiKey]);

  useEffect(() => { load(); }, [load]);

  const addRate = async () => {
    if (!newTask || !newPrice || !apiKey) return;
    setSaving(true);
    try {
      await billingApi.setRate(newTask, parseInt(newPrice), apiKey);
      setNewTask(""); setNewPrice("");
      load();
    } catch { /* silent */ }
    finally { setSaving(false); }
  };

  const removeRate = async (taskType: string) => {
    if (!apiKey) return;
    await billingApi.deleteRate(taskType, apiKey);
    load();
  };

  const fmt = (cents: number) => `$${(cents / 100).toFixed(2)}`;

  return (
    <div>
      <div style={{ marginBottom: 20, display: "flex", justifyContent: "space-between", alignItems: "flex-end" }}>
        <div>
          <h1 style={{ fontSize: 20, fontWeight: 700, letterSpacing: "-0.03em", margin: "0 0 3px" }}>Billing</h1>
          <p style={{ fontSize: 13, color: "rgba(255,255,255,0.35)", margin: 0 }}>
            Set rates for your agent's tasks and track payments
          </p>
        </div>
        <div style={{ display: "flex", gap: 24 }}>
          <Stat label="Earned" value={fmt(summary.earned)} color="#22c55e" />
          <Stat label="Spent"  value={fmt(summary.spent)}  color="#ef4444" />
          <Stat label="Rates"  value={rates.length} />
        </div>
      </div>

      {!apiKey && <AuthWarning />}

      {/* Rate card */}
      <div style={{ ...s.card, border: "1px solid rgba(99,235,165,0.1)", marginBottom: 20 }}>
        <div style={s.label}>RATE CARD — what you charge per task type</div>

        {rates.length > 0 && (
          <div style={{ marginBottom: 12 }}>
            {rates.map(r => (
              <div key={r.task_type} style={{ display: "flex", alignItems: "center", justifyContent: "space-between",
                padding: "8px 10px", borderRadius: 5, background: "rgba(255,255,255,0.03)",
                border: "1px solid rgba(255,255,255,0.06)", marginBottom: 6 }}>
                <span style={{ fontSize: 13, ...mono, color: "rgba(99,235,165,0.8)" }}>{r.task_type}</span>
                <div style={{ display: "flex", gap: 12, alignItems: "center" }}>
                  <span style={{ fontSize: 14, fontWeight: 700, color: "#fff", ...mono }}>{fmt(r.price_cents)}</span>
                  <span style={{ fontSize: 11, color: "rgba(255,255,255,0.25)" }}>per delegation</span>
                  <button onClick={() => removeRate(r.task_type)}
                    style={{ padding: "3px 8px", borderRadius: 4, fontSize: 11,
                      background: "rgba(239,68,68,0.08)", border: "1px solid rgba(239,68,68,0.15)",
                      color: "rgba(239,68,68,0.7)" }}>Remove</button>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Add new rate */}
        <div style={{ display: "flex", gap: 8, alignItems: "flex-end" }}>
          <div style={{ flex: 1 }}>
            <label style={s.label}>TASK TYPE</label>
            <input value={newTask} onChange={e => setNewTask(e.target.value)} placeholder="e.g. send_message"
              style={{ width: "100%", padding: "8px 10px", borderRadius: 5, fontSize: 13, ...mono,
                background: "rgba(255,255,255,0.04)", border: "1px solid rgba(255,255,255,0.1)",
                color: "#fff", outline: "none" }} />
          </div>
          <div style={{ width: 120 }}>
            <label style={s.label}>PRICE (CENTS)</label>
            <input value={newPrice} onChange={e => setNewPrice(e.target.value)} placeholder="e.g. 5" type="number" min="0"
              style={{ width: "100%", padding: "8px 10px", borderRadius: 5, fontSize: 13, ...mono,
                background: "rgba(255,255,255,0.04)", border: "1px solid rgba(255,255,255,0.1)",
                color: "#fff", outline: "none" }} />
          </div>
          <button onClick={addRate} disabled={saving || !newTask || !newPrice} style={{
            padding: "8px 14px", borderRadius: 5, fontSize: 13, fontWeight: 600,
            background: "rgba(99,235,165,0.1)", border: "1px solid rgba(99,235,165,0.25)",
            color: "rgba(99,235,165,0.85)", opacity: saving ? 0.6 : 1 }}>
            {saving ? "..." : "Add rate"}
          </button>
        </div>
      </div>

      {/* Transaction history */}
      <div style={{ ...s.card }}>
        <div style={s.label}>TRANSACTION HISTORY</div>
        {loading && <LoadingRow />}
        {txns.length === 0 && !loading && apiKey && (
          <EmptyState message="No transactions yet. Transactions are created automatically when an agent with a billing rate receives a delegation." />
        )}
        {txns.map(t => (
          <div key={t.id} style={{ display: "grid", gridTemplateColumns: "1fr 1fr 80px 80px 80px",
            gap: 12, padding: "10px 0", borderBottom: "1px solid rgba(255,255,255,0.04)",
            fontSize: 12, alignItems: "center" }}>
            <span style={{ color: "rgba(255,255,255,0.5)", ...mono, overflow: "hidden", textOverflow: "ellipsis" }}>
              {t.payer_agent_id.slice(0, 14)} → {t.payee_agent_id.slice(0, 14)}
            </span>
            <span style={{ color: "rgba(255,255,255,0.3)", ...mono, overflow: "hidden", textOverflow: "ellipsis" }}>
              {t.delegation_id.slice(0, 16)}
            </span>
            <span style={{ fontWeight: 700, color: t.payee_agent_id ? "#22c55e" : "#ef4444", ...mono }}>
              {fmt(t.amount_cents)}
            </span>
            <StatusBadge status={t.status} />
            <span style={{ color: "rgba(255,255,255,0.2)", fontSize: 11 }}>
              {new Date(t.created_at).toLocaleDateString()}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── Shared helpers ───────────────────────────────────────────
function AuthWarning() {
  return (
    <div style={{ padding: "14px 16px", borderRadius: 8, background: "rgba(251,191,36,0.05)",
      border: "1px solid rgba(251,191,36,0.15)", fontSize: 13, color: "rgba(251,191,36,0.7)", marginBottom: 20 }}>
      ⚠ Enter your API key in the header to view this data
    </div>
  );
}

function LoadingRow() {
  return <div style={{ color: "rgba(255,255,255,0.3)", fontSize: 13, padding: "8px 0" }}>Loading...</div>;
}

function EmptyState({ message }: { message: string }) {
  return (
    <div style={{ padding: "24px", borderRadius: 8, background: "rgba(255,255,255,0.01)",
      border: "1px dashed rgba(255,255,255,0.07)", textAlign: "center",
      color: "rgba(255,255,255,0.25)", fontSize: 13 }}>
      {message}
    </div>
  );
}
