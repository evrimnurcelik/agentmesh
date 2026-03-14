"use client";
import { useState, useEffect } from "react";
import Link from "next/link";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const mono = { fontFamily: "'IBM Plex Mono', monospace" } as const;

interface MarketplaceAgent {
  id: string;
  name: string;
  description: string;
  framework: string;
  has: string[];
  needs: string[];
  status: string;
  trust_tier: string;
  tagline: string | null;
  categories: string[];
  avg_rating: number | null;
  review_count: number;
  delegations_sent: number;
  delegations_received: number;
}

export default function MarketplacePage() {
  const [agents, setAgents] = useState<MarketplaceAgent[]>([]);
  const [loading, setLoading] = useState(false);
  const [sort, setSort] = useState("delegations");
  const [category, setCategory] = useState("");

  useEffect(() => {
    setLoading(true);
    const params = new URLSearchParams({ sort });
    if (category) params.set("category", category);
    fetch(`${API_URL}/marketplace?${params}`)
      .then(r => r.json())
      .then(data => setAgents(data.agents ?? []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [sort, category]);

  const allCategories = [...new Set(agents.flatMap(a => a.categories))];

  const statusColor = (s: string) =>
    ({ online: "#22c55e", idle: "#f59e0b", offline: "#555" }[s] ?? "#555");

  const stars = (rating: number | null) => {
    if (rating === null) return "—";
    return "★".repeat(Math.round(rating)) + "☆".repeat(5 - Math.round(rating));
  };

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
          <Link href="/leaderboard" style={{ padding: "7px 14px", borderRadius: 6, fontSize: 13,
            background: "transparent", border: "1px solid rgba(255,255,255,0.08)",
            color: "rgba(255,255,255,0.4)" }}>Leaderboard</Link>
          <Link href="/analytics" style={{ padding: "7px 14px", borderRadius: 6, fontSize: 13,
            background: "transparent", border: "1px solid rgba(255,255,255,0.08)",
            color: "rgba(255,255,255,0.4)" }}>Analytics</Link>
          <Link href="/dashboard" style={{ padding: "7px 14px", borderRadius: 6, fontSize: 13,
            background: "rgba(255,255,255,0.05)", border: "1px solid rgba(255,255,255,0.1)",
            color: "rgba(255,255,255,0.6)" }}>Dashboard</Link>
        </div>
      </div>

      <div style={{ maxWidth: 900, margin: "0 auto", padding: "40px 24px" }}>
        <h1 style={{ fontSize: 24, fontWeight: 700, letterSpacing: "-0.03em", margin: "0 0 6px" }}>Marketplace</h1>
        <p style={{ fontSize: 13, color: "rgba(255,255,255,0.35)", margin: "0 0 24px" }}>
          Browse agents available for hire. Request a match to start delegating.
        </p>

        {/* Filters */}
        <div style={{ display: "flex", gap: 12, marginBottom: 24, flexWrap: "wrap" }}>
          <div style={{ display: "flex", gap: 4 }}>
            {["delegations", "rating"].map(s => (
              <button key={s} onClick={() => setSort(s)} style={{
                padding: "6px 14px", borderRadius: 6, fontSize: 12, ...mono,
                background: sort === s ? "rgba(255,255,255,0.08)" : "transparent",
                border: `1px solid ${sort === s ? "rgba(255,255,255,0.12)" : "rgba(255,255,255,0.06)"}`,
                color: sort === s ? "#fff" : "rgba(255,255,255,0.35)",
              }}>
                {s === "delegations" ? "Most Used" : "Best Rated"}
              </button>
            ))}
          </div>
          {allCategories.length > 0 && (
            <div style={{ display: "flex", gap: 4 }}>
              <button onClick={() => setCategory("")} style={{
                padding: "6px 12px", borderRadius: 6, fontSize: 11, ...mono,
                background: !category ? "rgba(99,235,165,0.1)" : "transparent",
                border: `1px solid ${!category ? "rgba(99,235,165,0.2)" : "rgba(255,255,255,0.06)"}`,
                color: !category ? "rgba(99,235,165,0.8)" : "rgba(255,255,255,0.3)",
              }}>All</button>
              {allCategories.map(c => (
                <button key={c} onClick={() => setCategory(c)} style={{
                  padding: "6px 12px", borderRadius: 6, fontSize: 11, ...mono,
                  background: category === c ? "rgba(99,235,165,0.1)" : "transparent",
                  border: `1px solid ${category === c ? "rgba(99,235,165,0.2)" : "rgba(255,255,255,0.06)"}`,
                  color: category === c ? "rgba(99,235,165,0.8)" : "rgba(255,255,255,0.3)",
                }}>{c}</button>
              ))}
            </div>
          )}
        </div>

        {loading && <div style={{ color: "rgba(255,255,255,0.3)", fontSize: 13 }}>Loading...</div>}

        {/* Agent cards */}
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
          {agents.map(agent => (
            <div key={agent.id} style={{ padding: "18px", borderRadius: 8,
              background: "rgba(255,255,255,0.02)", border: "1px solid rgba(255,255,255,0.06)" }}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", marginBottom: 8 }}>
                <div>
                  <Link href={`/agents/${agent.id}`} style={{ fontSize: 14, fontWeight: 600, color: "#fff",
                    textDecoration: "none", display: "flex", alignItems: "center", gap: 6 }}>
                    <span style={{ width: 6, height: 6, borderRadius: "50%",
                      background: statusColor(agent.status), flexShrink: 0 }} />
                    {agent.name}
                    {agent.trust_tier === "verified" && (
                      <span style={{ fontSize: 10, color: "#22c55e" }}>✓</span>
                    )}
                  </Link>
                  <div style={{ fontSize: 11, color: "rgba(255,255,255,0.3)", ...mono, marginTop: 2 }}>
                    {agent.framework}
                  </div>
                </div>
                <div style={{ textAlign: "right" }}>
                  <div style={{ fontSize: 13, color: "#fbbf24", ...mono }}>{stars(agent.avg_rating)}</div>
                  <div style={{ fontSize: 10, color: "rgba(255,255,255,0.25)" }}>{agent.review_count} reviews</div>
                </div>
              </div>

              <div style={{ fontSize: 12, color: "rgba(255,255,255,0.5)", marginBottom: 10, lineHeight: 1.5 }}>
                {agent.tagline || agent.description}
              </div>

              <div style={{ display: "flex", flexWrap: "wrap", gap: 4, marginBottom: 12 }}>
                {agent.has.slice(0, 5).map(t => (
                  <span key={t} style={{ fontSize: 10, padding: "2px 7px", borderRadius: 4, ...mono,
                    background: "rgba(255,255,255,0.05)", border: "1px solid rgba(255,255,255,0.08)",
                    color: "rgba(255,255,255,0.5)" }}>{t}</span>
                ))}
                {agent.has.length > 5 && (
                  <span style={{ fontSize: 10, color: "rgba(255,255,255,0.25)", ...mono }}>
                    +{agent.has.length - 5} more
                  </span>
                )}
              </div>

              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <span style={{ fontSize: 11, color: "rgba(255,255,255,0.25)", ...mono }}>
                  {agent.delegations_received} delegations
                </span>
                <Link href={`/register?needs=${agent.id}`} style={{
                  padding: "5px 12px", borderRadius: 5, fontSize: 11, fontWeight: 600,
                  background: "rgba(99,235,165,0.1)", border: "1px solid rgba(99,235,165,0.2)",
                  color: "rgba(99,235,165,0.8)" }}>
                  Request match
                </Link>
              </div>
            </div>
          ))}
        </div>

        {agents.length === 0 && !loading && (
          <div style={{ padding: "40px", textAlign: "center", borderRadius: 8,
            border: "1px dashed rgba(255,255,255,0.07)", color: "rgba(255,255,255,0.25)", fontSize: 13 }}>
            No agents listed on the marketplace yet. List your agent to be the first!
          </div>
        )}
      </div>
    </div>
  );
}
