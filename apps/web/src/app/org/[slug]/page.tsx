"use client";
import { useState, useEffect } from "react";
import Link from "next/link";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const mono = { fontFamily: "'IBM Plex Mono', monospace" } as const;

interface OrgMember {
  email: string;
  role: string;
  invited_at: string;
  joined_at: string | null;
}

interface OrgData {
  id: string;
  name: string;
  slug: string;
  plan: string;
  members: OrgMember[];
  created_at: string;
}

interface OrgAgent {
  id: string;
  name: string;
  framework: string;
  status: string;
  has: string[];
  needs: string[];
}

export default function OrgPage({ params }: { params: Promise<{ slug: string }> }) {
  const [org, setOrg] = useState<OrgData | null>(null);
  const [agents, setAgents] = useState<OrgAgent[]>([]);
  const [loading, setLoading] = useState(true);
  const [slug, setSlug] = useState("");

  useEffect(() => {
    params.then(p => {
      setSlug(p.slug);
      // For now, we fetch by org ID — in production, add a slug lookup endpoint
      setLoading(false);
    });
  }, [params]);

  useEffect(() => {
    if (!slug) return;
    // Try fetching by slug (which could be the org ID)
    fetch(`${API_URL}/orgs/${slug}`)
      .then(r => r.json())
      .then(data => {
        if (data.id) {
          setOrg(data);
          // Fetch org agents
          fetch(`${API_URL}/orgs/${data.id}/agents`)
            .then(r => r.json())
            .then(d => setAgents(d.agents ?? []))
            .catch(() => {});
        }
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [slug]);

  const ROLE_COLORS: Record<string, string> = {
    owner: "#22c55e", admin: "#6364eb", member: "rgba(255,255,255,0.5)", viewer: "rgba(255,255,255,0.3)",
  };

  const statusColor = (s: string) =>
    ({ online: "#22c55e", idle: "#f59e0b", offline: "#555" }[s] ?? "#555");

  if (loading) return (
    <div style={{ minHeight: "100vh", background: "#0a0a0f", color: "#fff",
      display: "flex", alignItems: "center", justifyContent: "center" }}>
      <div style={{ fontSize: 13, color: "rgba(255,255,255,0.3)" }}>Loading...</div>
    </div>
  );

  if (!org) return (
    <div style={{ minHeight: "100vh", background: "#0a0a0f", color: "#fff",
      display: "flex", alignItems: "center", justifyContent: "center" }}>
      <div style={{ textAlign: "center" }}>
        <h1 style={{ fontSize: 20, fontWeight: 700, marginBottom: 8 }}>Organization not found</h1>
        <Link href="/dashboard" style={{ fontSize: 13, color: "rgba(99,235,165,0.7)" }}>Go to dashboard</Link>
      </div>
    </div>
  );

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
        <Link href="/dashboard" style={{ padding: "7px 14px", borderRadius: 6, fontSize: 13,
          background: "rgba(255,255,255,0.05)", border: "1px solid rgba(255,255,255,0.1)",
          color: "rgba(255,255,255,0.6)" }}>Dashboard</Link>
      </div>

      <div style={{ maxWidth: 800, margin: "0 auto", padding: "40px 24px" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 24 }}>
          <h1 style={{ fontSize: 24, fontWeight: 700, letterSpacing: "-0.03em", margin: 0 }}>{org.name}</h1>
          <span style={{ padding: "3px 10px", borderRadius: 20, fontSize: 11, ...mono,
            background: "rgba(99,235,165,0.1)", border: "1px solid rgba(99,235,165,0.2)",
            color: "rgba(99,235,165,0.7)" }}>{org.plan}</span>
        </div>

        {/* Agents */}
        <div style={{ marginBottom: 32 }}>
          <div style={{ fontSize: 11, color: "rgba(255,255,255,0.25)", ...mono, letterSpacing: "0.08em", marginBottom: 12 }}>
            AGENTS ({agents.length})
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
            {agents.map(a => (
              <Link key={a.id} href={`/agents/${a.id}`} style={{
                padding: "14px 16px", borderRadius: 8, background: "rgba(255,255,255,0.02)",
                border: "1px solid rgba(255,255,255,0.06)", display: "block", textDecoration: "none", color: "#fff" }}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 4 }}>
                  <span style={{ fontSize: 13, fontWeight: 600 }}>{a.name}</span>
                  <span style={{ display: "inline-flex", alignItems: "center", gap: 4 }}>
                    <span style={{ width: 6, height: 6, borderRadius: "50%", background: statusColor(a.status) }} />
                    <span style={{ fontSize: 10, color: "rgba(255,255,255,0.3)", ...mono }}>{a.status}</span>
                  </span>
                </div>
                <div style={{ fontSize: 11, color: "rgba(255,255,255,0.3)", ...mono }}>{a.framework}</div>
              </Link>
            ))}
          </div>
          {agents.length === 0 && (
            <div style={{ padding: "20px", textAlign: "center", color: "rgba(255,255,255,0.25)", fontSize: 13,
              border: "1px dashed rgba(255,255,255,0.07)", borderRadius: 8 }}>
              No agents in this org yet.
            </div>
          )}
        </div>

        {/* Members */}
        <div>
          <div style={{ fontSize: 11, color: "rgba(255,255,255,0.25)", ...mono, letterSpacing: "0.08em", marginBottom: 12 }}>
            MEMBERS ({org.members.length})
          </div>
          <div style={{ borderRadius: 8, border: "1px solid rgba(255,255,255,0.06)", overflow: "hidden" }}>
            {org.members.map(m => (
              <div key={m.email} style={{ display: "flex", justifyContent: "space-between", alignItems: "center",
                padding: "12px 16px", borderBottom: "1px solid rgba(255,255,255,0.04)" }}>
                <span style={{ fontSize: 13, color: "rgba(255,255,255,0.7)", ...mono }}>{m.email}</span>
                <span style={{ padding: "2px 8px", borderRadius: 4, fontSize: 10, ...mono,
                  background: `${ROLE_COLORS[m.role] ?? "rgba(255,255,255,0.1)"}20`,
                  color: ROLE_COLORS[m.role] ?? "rgba(255,255,255,0.4)" }}>{m.role}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
