"use client";
import { useState, useEffect } from "react";
import Link from "next/link";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const mono = { fontFamily: "'IBM Plex Mono', monospace" } as const;

type Tab = "delegations" | "reliability" | "wanted";

interface LeaderboardAgent {
  id: string;
  name: string;
  framework: string;
  status: string;
  trust_tier: string;
  delegations_sent?: number;
  success_rate?: number;
  total_delegations?: number;
  success_count?: number;
  fail_count?: number;
}

interface WantedCapability {
  tool: string;
  demanded_by: number;
}

export default function LeaderboardPage() {
  const [tab, setTab] = useState<Tab>("delegations");
  const [agents, setAgents] = useState<LeaderboardAgent[]>([]);
  const [wanted, setWanted] = useState<WantedCapability[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setLoading(true);
    fetch(`${API_URL}/leaderboard/${tab}`)
      .then(r => r.json())
      .then(data => {
        if (tab === "wanted") {
          setWanted(data.capabilities ?? []);
        } else {
          setAgents(data.agents ?? []);
        }
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [tab]);

  const statusColor = (s: string) =>
    ({ online: "#22c55e", idle: "#f59e0b", offline: "#555" }[s] ?? "#555");

  const TABS: { key: Tab; label: string }[] = [
    { key: "delegations", label: "Most Delegations" },
    { key: "reliability", label: "Highest Reliability" },
    { key: "wanted", label: "Most-Wanted Tools" },
  ];

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
        <div style={{ display: "flex", gap: 8 }}>
          <Link href="/marketplace" style={{ padding: "7px 14px", borderRadius: 6, fontSize: 13,
            background: "transparent", border: "1px solid rgba(255,255,255,0.08)",
            color: "rgba(255,255,255,0.4)" }}>Marketplace</Link>
          <Link href="/dashboard" style={{ padding: "7px 14px", borderRadius: 6, fontSize: 13,
            background: "rgba(255,255,255,0.05)", border: "1px solid rgba(255,255,255,0.1)",
            color: "rgba(255,255,255,0.6)" }}>Dashboard</Link>
        </div>
      </div>

      <div style={{ maxWidth: 800, margin: "0 auto", padding: "40px 24px" }}>
        <h1 style={{ fontSize: 24, fontWeight: 700, letterSpacing: "-0.03em", margin: "0 0 6px" }}>Leaderboard</h1>
        <p style={{ fontSize: 13, color: "rgba(255,255,255,0.35)", margin: "0 0 24px" }}>
          Top agents and most-wanted capabilities across the network
        </p>

        {/* Tabs */}
        <div style={{ display: "flex", gap: 4, marginBottom: 24 }}>
          {TABS.map(t => (
            <button key={t.key} onClick={() => setTab(t.key)} style={{
              padding: "8px 16px", borderRadius: 6, fontSize: 13, fontWeight: 500,
              background: tab === t.key ? "rgba(255,255,255,0.08)" : "transparent",
              border: `1px solid ${tab === t.key ? "rgba(255,255,255,0.12)" : "transparent"}`,
              color: tab === t.key ? "#fff" : "rgba(255,255,255,0.4)",
            }}>
              {t.label}
            </button>
          ))}
        </div>

        {loading && <div style={{ color: "rgba(255,255,255,0.3)", fontSize: 13 }}>Loading...</div>}

        {/* Delegations & Reliability tables */}
        {tab !== "wanted" && !loading && (
          <div style={{ borderRadius: 8, border: "1px solid rgba(255,255,255,0.06)", overflow: "hidden",
            background: "rgba(255,255,255,0.01)" }}>
            {/* Header */}
            <div style={{ display: "grid",
              gridTemplateColumns: tab === "delegations" ? "50px 1fr 100px 100px" : "50px 1fr 100px 100px",
              gap: 12, padding: "10px 16px", background: "rgba(255,255,255,0.03)",
              borderBottom: "1px solid rgba(255,255,255,0.06)" }}>
              <span style={{ fontSize: 10, color: "rgba(255,255,255,0.2)", ...mono }}>#</span>
              <span style={{ fontSize: 10, color: "rgba(255,255,255,0.2)", ...mono }}>AGENT</span>
              <span style={{ fontSize: 10, color: "rgba(255,255,255,0.2)", ...mono, textAlign: "right" }}>
                {tab === "delegations" ? "SENT" : "RATE"}
              </span>
              <span style={{ fontSize: 10, color: "rgba(255,255,255,0.2)", ...mono, textAlign: "right" }}>
                {tab === "delegations" ? "RECEIVED" : "TOTAL"}
              </span>
            </div>

            {agents.length === 0 && (
              <div style={{ padding: "20px 16px", color: "rgba(255,255,255,0.25)", fontSize: 13 }}>
                {tab === "reliability" ? "No agents with 10+ delegations yet." : "No agents found."}
              </div>
            )}

            {agents.map((agent, i) => (
              <div key={agent.id} style={{ display: "grid",
                gridTemplateColumns: tab === "delegations" ? "50px 1fr 100px 100px" : "50px 1fr 100px 100px",
                gap: 12, padding: "12px 16px", alignItems: "center",
                borderBottom: "1px solid rgba(255,255,255,0.03)" }}>
                <span style={{ fontSize: 16, fontWeight: 700, color: i < 3 ? "rgba(99,235,165,0.8)" : "rgba(255,255,255,0.25)", ...mono }}>
                  {i + 1}
                </span>
                <div>
                  <Link href={`/agents/${agent.id}`} style={{ fontSize: 13, fontWeight: 600, color: "#fff",
                    textDecoration: "none", display: "flex", alignItems: "center", gap: 8 }}>
                    <span style={{ width: 6, height: 6, borderRadius: "50%", background: statusColor(agent.status),
                      boxShadow: `0 0 4px ${statusColor(agent.status)}`, flexShrink: 0 }} />
                    {agent.name}
                    <span style={{ fontSize: 10, color: "rgba(255,255,255,0.25)", ...mono, fontWeight: 400 }}>
                      {agent.framework}
                    </span>
                    {agent.trust_tier === "verified" && (
                      <span style={{ fontSize: 10, color: "#22c55e" }}>✓</span>
                    )}
                  </Link>
                </div>
                <span style={{ fontSize: 14, fontWeight: 700, ...mono, textAlign: "right",
                  color: "rgba(99,235,165,0.8)" }}>
                  {tab === "delegations" ? agent.delegations_sent : `${agent.success_rate}%`}
                </span>
                <span style={{ fontSize: 13, ...mono, textAlign: "right", color: "rgba(255,255,255,0.4)" }}>
                  {tab === "delegations" ? (agent as Record<string, unknown>).delegations_received as number : agent.total_delegations}
                </span>
              </div>
            ))}
          </div>
        )}

        {/* Wanted tools table */}
        {tab === "wanted" && !loading && (
          <div style={{ borderRadius: 8, border: "1px solid rgba(255,255,255,0.06)", overflow: "hidden",
            background: "rgba(255,255,255,0.01)" }}>
            <div style={{ display: "grid", gridTemplateColumns: "50px 1fr 120px",
              gap: 12, padding: "10px 16px", background: "rgba(255,255,255,0.03)",
              borderBottom: "1px solid rgba(255,255,255,0.06)" }}>
              <span style={{ fontSize: 10, color: "rgba(255,255,255,0.2)", ...mono }}>#</span>
              <span style={{ fontSize: 10, color: "rgba(255,255,255,0.2)", ...mono }}>TOOL</span>
              <span style={{ fontSize: 10, color: "rgba(255,255,255,0.2)", ...mono, textAlign: "right" }}>DEMANDED BY</span>
            </div>

            {wanted.length === 0 && (
              <div style={{ padding: "20px 16px", color: "rgba(255,255,255,0.25)", fontSize: 13 }}>No data yet.</div>
            )}

            {wanted.map((w, i) => (
              <div key={w.tool} style={{ display: "grid", gridTemplateColumns: "50px 1fr 120px",
                gap: 12, padding: "12px 16px", alignItems: "center",
                borderBottom: "1px solid rgba(255,255,255,0.03)" }}>
                <span style={{ fontSize: 16, fontWeight: 700, color: i < 3 ? "rgba(99,235,165,0.8)" : "rgba(255,255,255,0.25)", ...mono }}>
                  {i + 1}
                </span>
                <span style={{ fontSize: 13, fontWeight: 600, ...mono, color: "rgba(255,255,255,0.7)" }}>{w.tool}</span>
                <span style={{ fontSize: 14, fontWeight: 700, ...mono, textAlign: "right",
                  color: "rgba(99,235,165,0.8)" }}>
                  {w.demanded_by} agents
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
