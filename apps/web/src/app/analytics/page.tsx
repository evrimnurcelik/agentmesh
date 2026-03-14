"use client";
import { useState, useEffect, useCallback } from "react";
import Link from "next/link";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const mono = { fontFamily: "'IBM Plex Mono', monospace" } as const;

interface DelegationDay {
  date: string;
  total: number;
  completed: number;
  failed: number;
  running: number;
}

interface LatencyTask {
  task: string;
  count: number;
  avg_ms: number;
  p50_ms: number | null;
  p95_ms: number | null;
  p99_ms: number | null;
}

interface TopPair {
  pair: string;
  count: number;
  completed: number;
  avg_duration_ms: number;
}

async function fetchJson<T>(url: string, apiKey: string): Promise<T | null> {
  try {
    const res = await fetch(url, {
      headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
    });
    if (!res.ok) return null;
    return res.json();
  } catch {
    return null;
  }
}

export default function AnalyticsPage() {
  const [apiKey, setApiKey] = useState("");
  const [keyInput, setKeyInput] = useState("");
  const [days, setDays] = useState(7);
  const [delegations, setDelegations] = useState<DelegationDay[]>([]);
  const [latency, setLatency] = useState<LatencyTask[]>([]);
  const [pairs, setPairs] = useState<TopPair[]>([]);
  const [summary, setSummary] = useState({ total: 0, success_rate: 0, avg_latency_ms: 0 });
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    if (!apiKey) return;
    setLoading(true);
    const [delData, latData, pairData] = await Promise.all([
      fetchJson<{ series: DelegationDay[]; summary: typeof summary }>(`${API_URL}/analytics/delegations?days=${days}`, apiKey),
      fetchJson<{ by_task: LatencyTask[] }>(`${API_URL}/analytics/latency?days=${days}`, apiKey),
      fetchJson<{ pairs: TopPair[] }>(`${API_URL}/analytics/top-pairs?days=${days}`, apiKey),
    ]);
    if (delData) { setDelegations(delData.series); setSummary(delData.summary); }
    if (latData) setLatency(latData.by_task);
    if (pairData) setPairs(pairData.pairs);
    setLoading(false);
  }, [apiKey, days]);

  useEffect(() => { load(); }, [load]);

  const maxDelegations = Math.max(...delegations.map(d => d.total), 1);

  return (
    <div style={{ minHeight: "100vh", background: "#0a0a0f", color: "#fff",
      backgroundImage: "radial-gradient(ellipse 80% 40% at 50% -10%, rgba(99,235,165,0.05) 0%, transparent 60%)" }}>

      {/* Nav */}
      <div style={{ padding: "18px 32px", borderBottom: "1px solid rgba(255,255,255,0.06)",
        display: "flex", alignItems: "center", justifyContent: "space-between",
        backdropFilter: "blur(12px)", background: "rgba(10,10,15,0.85)" }}>
        <Link href="/" style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <div style={{ width: 26, height: 26, borderRadius: 6, fontSize: 13,
            background: "linear-gradient(135deg, rgba(99,235,165,0.8), rgba(99,100,235,0.6))",
            display: "flex", alignItems: "center", justifyContent: "center" }}>⬡</div>
          <span style={{ fontSize: 15, fontWeight: 700, letterSpacing: "-0.02em" }}>AgentMesh</span>
        </Link>
        <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
          <Link href="/leaderboard" style={{ padding: "7px 14px", borderRadius: 6, fontSize: 13,
            background: "transparent", border: "1px solid rgba(255,255,255,0.08)",
            color: "rgba(255,255,255,0.4)" }}>Leaderboard</Link>
          <Link href="/marketplace" style={{ padding: "7px 14px", borderRadius: 6, fontSize: 13,
            background: "transparent", border: "1px solid rgba(255,255,255,0.08)",
            color: "rgba(255,255,255,0.4)" }}>Marketplace</Link>
          <Link href="/dashboard" style={{ padding: "7px 14px", borderRadius: 6, fontSize: 13,
            background: "rgba(255,255,255,0.05)", border: "1px solid rgba(255,255,255,0.1)",
            color: "rgba(255,255,255,0.6)" }}>Dashboard</Link>
        </div>
      </div>

      <div style={{ maxWidth: 900, margin: "0 auto", padding: "40px 24px" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", marginBottom: 24 }}>
          <div>
            <h1 style={{ fontSize: 24, fontWeight: 700, letterSpacing: "-0.03em", margin: "0 0 6px" }}>Analytics</h1>
            <p style={{ fontSize: 13, color: "rgba(255,255,255,0.35)", margin: 0 }}>
              Delegation volume, latency, cost, and reliability metrics
            </p>
          </div>
          <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <input placeholder="API key..." value={keyInput}
              onChange={e => setKeyInput(e.target.value)}
              onKeyDown={e => e.key === "Enter" && setApiKey(keyInput)}
              style={{ padding: "7px 12px", borderRadius: 6, fontSize: 12, ...mono,
                background: "rgba(255,255,255,0.04)", border: `1px solid ${apiKey ? "rgba(99,235,165,0.25)" : "rgba(255,255,255,0.08)"}`,
                color: "#fff", outline: "none", width: 180 }} />
            {!apiKey && <button onClick={() => setApiKey(keyInput)} style={{ padding: "7px 12px", borderRadius: 6, fontSize: 12,
              background: "rgba(255,255,255,0.05)", border: "1px solid rgba(255,255,255,0.1)",
              color: "rgba(255,255,255,0.5)" }}>Set key</button>}
          </div>
        </div>

        {!apiKey && (
          <div style={{ padding: "14px 16px", borderRadius: 8, background: "rgba(251,191,36,0.05)",
            border: "1px solid rgba(251,191,36,0.15)", fontSize: 13, color: "rgba(251,191,36,0.7)", marginBottom: 24 }}>
            Enter your API key to view analytics
          </div>
        )}

        {apiKey && (
          <>
            {/* Period selector */}
            <div style={{ display: "flex", gap: 4, marginBottom: 24 }}>
              {[7, 30, 90].map(d => (
                <button key={d} onClick={() => setDays(d)} style={{
                  padding: "6px 14px", borderRadius: 6, fontSize: 12, ...mono,
                  background: days === d ? "rgba(255,255,255,0.08)" : "transparent",
                  border: `1px solid ${days === d ? "rgba(255,255,255,0.12)" : "transparent"}`,
                  color: days === d ? "#fff" : "rgba(255,255,255,0.35)",
                }}>{d}d</button>
              ))}
            </div>

            {loading && <div style={{ color: "rgba(255,255,255,0.3)", fontSize: 13, marginBottom: 20 }}>Loading...</div>}

            {/* Summary stats */}
            <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: 12, marginBottom: 32 }}>
              {[
                { label: "Total Delegations", value: summary.total, color: "rgba(255,255,255,0.6)" },
                { label: "Success Rate", value: `${Math.round(summary.success_rate * 100)}%`, color: "#22c55e" },
                { label: "Avg Latency", value: `${summary.avg_latency_ms}ms`, color: "rgba(99,235,165,0.8)" },
              ].map(s => (
                <div key={s.label} style={{ padding: "18px", borderRadius: 8, background: "rgba(255,255,255,0.02)",
                  border: "1px solid rgba(255,255,255,0.06)", textAlign: "center" }}>
                  <div style={{ fontSize: 28, fontWeight: 700, color: s.color, ...mono, lineHeight: 1 }}>{s.value}</div>
                  <div style={{ fontSize: 10, color: "rgba(255,255,255,0.3)", ...mono, marginTop: 6 }}>{s.label}</div>
                </div>
              ))}
            </div>

            {/* Bar chart (CSS-based) */}
            {delegations.length > 0 && (
              <div style={{ marginBottom: 32 }}>
                <div style={{ fontSize: 11, color: "rgba(255,255,255,0.25)", ...mono, letterSpacing: "0.08em", marginBottom: 12 }}>
                  DELEGATION VOLUME
                </div>
                <div style={{ display: "flex", gap: 2, alignItems: "flex-end", height: 120, padding: "0 4px" }}>
                  {delegations.map(d => (
                    <div key={d.date} style={{ flex: 1, display: "flex", flexDirection: "column", alignItems: "center", gap: 2 }}>
                      <div style={{ width: "100%", display: "flex", flexDirection: "column", justifyContent: "flex-end",
                        height: 100 }}>
                        <div style={{ width: "100%", borderRadius: "3px 3px 0 0",
                          background: "rgba(99,235,165,0.4)",
                          height: `${(d.completed / maxDelegations) * 100}%`, minHeight: d.completed > 0 ? 2 : 0 }} />
                        <div style={{ width: "100%",
                          background: "rgba(239,68,68,0.4)",
                          height: `${(d.failed / maxDelegations) * 100}%`, minHeight: d.failed > 0 ? 2 : 0 }} />
                      </div>
                      <div style={{ fontSize: 8, color: "rgba(255,255,255,0.2)", ...mono, transform: "rotate(-45deg)",
                        whiteSpace: "nowrap" }}>
                        {d.date.slice(5)}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Latency by task */}
            {latency.length > 0 && (
              <div style={{ marginBottom: 32 }}>
                <div style={{ fontSize: 11, color: "rgba(255,255,255,0.25)", ...mono, letterSpacing: "0.08em", marginBottom: 12 }}>
                  LATENCY BY TASK TYPE
                </div>
                <div style={{ borderRadius: 8, border: "1px solid rgba(255,255,255,0.06)", overflow: "hidden" }}>
                  <div style={{ display: "grid", gridTemplateColumns: "1fr 60px 80px 80px 80px",
                    gap: 12, padding: "10px 16px", background: "rgba(255,255,255,0.03)",
                    borderBottom: "1px solid rgba(255,255,255,0.06)" }}>
                    {["TASK", "COUNT", "AVG", "P50", "P95"].map(h => (
                      <span key={h} style={{ fontSize: 10, color: "rgba(255,255,255,0.2)", ...mono }}>{h}</span>
                    ))}
                  </div>
                  {latency.map(l => (
                    <div key={l.task} style={{ display: "grid", gridTemplateColumns: "1fr 60px 80px 80px 80px",
                      gap: 12, padding: "10px 16px", borderBottom: "1px solid rgba(255,255,255,0.03)",
                      fontSize: 12, ...mono }}>
                      <span style={{ color: "rgba(255,255,255,0.7)" }}>{l.task}</span>
                      <span style={{ color: "rgba(255,255,255,0.4)" }}>{l.count}</span>
                      <span style={{ color: "rgba(99,235,165,0.8)" }}>{l.avg_ms}ms</span>
                      <span style={{ color: "rgba(255,255,255,0.4)" }}>{l.p50_ms ?? "—"}ms</span>
                      <span style={{ color: "rgba(255,255,255,0.4)" }}>{l.p95_ms ?? "—"}ms</span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Top pairs */}
            {pairs.length > 0 && (
              <div>
                <div style={{ fontSize: 11, color: "rgba(255,255,255,0.25)", ...mono, letterSpacing: "0.08em", marginBottom: 12 }}>
                  MOST ACTIVE AGENT PAIRS
                </div>
                <div style={{ borderRadius: 8, border: "1px solid rgba(255,255,255,0.06)", overflow: "hidden" }}>
                  {pairs.map((p, i) => (
                    <div key={p.pair} style={{ display: "grid", gridTemplateColumns: "30px 1fr 80px 80px",
                      gap: 12, padding: "10px 16px", borderBottom: "1px solid rgba(255,255,255,0.03)",
                      fontSize: 12, alignItems: "center" }}>
                      <span style={{ fontWeight: 700, color: i < 3 ? "rgba(99,235,165,0.8)" : "rgba(255,255,255,0.25)", ...mono }}>
                        {i + 1}
                      </span>
                      <span style={{ color: "rgba(255,255,255,0.6)", ...mono, overflow: "hidden", textOverflow: "ellipsis" }}>
                        {p.pair}
                      </span>
                      <span style={{ color: "rgba(99,235,165,0.8)", fontWeight: 700, ...mono }}>{p.count}</span>
                      <span style={{ color: "rgba(255,255,255,0.4)", ...mono }}>{p.avg_duration_ms}ms</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
