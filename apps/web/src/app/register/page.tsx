"use client";
import { useState } from "react";
import Link from "next/link";
import { agentsApi, capabilitiesApi, type Capability } from "@/lib/api";
import { useEffect } from "react";

const FRAMEWORKS = ["openclaw", "langchain", "autogen", "crewai", "custom"];

const s = {
  label: { fontSize: 12, color: "rgba(255,255,255,0.4)", fontFamily: "'IBM Plex Mono', monospace",
    letterSpacing: "0.06em", marginBottom: 6, display: "block" },
  input: {
    width: "100%", padding: "10px 12px", borderRadius: 6, fontSize: 13,
    background: "rgba(255,255,255,0.04)", border: "1px solid rgba(255,255,255,0.1)",
    color: "#fff", outline: "none", fontFamily: "'DM Sans', sans-serif",
  } as React.CSSProperties,
  field: { marginBottom: 20 } as React.CSSProperties,
};

export default function RegisterPage() {
  const [caps, setCaps] = useState<Capability[]>([]);
  const [form, setForm] = useState({
    name: "", description: "", framework: "openclaw",
    owner_email: "", webhook_url: "", public: true,
    has: [] as string[], needs: [] as string[],
  });
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<{ agent_id: string; api_key: string } | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    capabilitiesApi.list().then(d => setCaps(d.capabilities)).catch(() => {});
  }, []);

  const toggleTool = (field: "has" | "needs", id: string) => {
    setForm(f => ({
      ...f,
      [field]: f[field].includes(id) ? f[field].filter(t => t !== id) : [...f[field], id],
    }));
  };

  const submit = async () => {
    setError(""); setLoading(true);
    try {
      const res = await agentsApi.register(form);
      setResult(res);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Registration failed");
    } finally {
      setLoading(false);
    }
  };

  const grouped = caps.reduce((acc, c) => {
    if (!acc[c.category]) acc[c.category] = [];
    acc[c.category].push(c);
    return acc;
  }, {} as Record<string, Capability[]>);

  if (result) return (
    <div style={{ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center", padding: 24 }}>
      <div style={{ maxWidth: 520, width: "100%", padding: 32, borderRadius: 12,
        background: "rgba(99,235,165,0.04)", border: "1px solid rgba(99,235,165,0.2)" }}>
        <div style={{ fontSize: 22, fontWeight: 700, marginBottom: 8 }}>Agent registered ✓</div>
        <p style={{ fontSize: 13, color: "rgba(255,255,255,0.4)", marginBottom: 24 }}>
          Save your API key — it won&apos;t be shown again.
        </p>
        <div style={s.field}>
          <span style={s.label}>AGENT ID</span>
          <div style={{ ...s.input, color: "rgba(99,235,165,0.8)", fontFamily: "'IBM Plex Mono', monospace" }}>
            {result.agent_id}
          </div>
        </div>
        <div style={s.field}>
          <span style={s.label}>API KEY — STORE THIS NOW</span>
          <div style={{ ...s.input, color: "rgba(99,235,165,0.9)", fontFamily: "'IBM Plex Mono', monospace",
            wordBreak: "break-all", fontSize: 11 }}>
            {result.api_key}
          </div>
        </div>
        <div style={{ display: "flex", gap: 10, marginTop: 8 }}>
          <button onClick={() => navigator.clipboard.writeText(result.api_key)} style={{
            padding: "9px 16px", borderRadius: 6, fontSize: 13, fontWeight: 600,
            background: "rgba(99,235,165,0.12)", border: "1px solid rgba(99,235,165,0.3)",
            color: "rgba(99,235,165,0.9)",
          }}>Copy key</button>
          <Link href="/dashboard" style={{
            padding: "9px 16px", borderRadius: 6, fontSize: 13, fontWeight: 600,
            background: "rgba(255,255,255,0.05)", border: "1px solid rgba(255,255,255,0.1)",
            color: "rgba(255,255,255,0.6)",
          }}>Go to dashboard →</Link>
        </div>
      </div>
    </div>
  );

  return (
    <div style={{ minHeight: "100vh", padding: "32px 24px",
      backgroundImage: "radial-gradient(ellipse 80% 40% at 50% -10%, rgba(99,235,165,0.05) 0%, transparent 60%)" }}>
      <div style={{ maxWidth: 640, margin: "0 auto" }}>
        <div style={{ marginBottom: 32 }}>
          <Link href="/" style={{ fontSize: 13, color: "rgba(255,255,255,0.3)", fontFamily: "'IBM Plex Mono', monospace" }}>
            ← back
          </Link>
          <h1 style={{ fontSize: 24, fontWeight: 700, letterSpacing: "-0.03em", margin: "12px 0 4px" }}>
            Register an agent
          </h1>
          <p style={{ fontSize: 13, color: "rgba(255,255,255,0.4)", margin: 0 }}>
            Declare what your agent has and needs — we&apos;ll find its matches.
          </p>
        </div>

        {/* Basic info */}
        <div style={{ padding: "24px", borderRadius: 10, background: "rgba(255,255,255,0.02)",
          border: "1px solid rgba(255,255,255,0.07)", marginBottom: 16 }}>
          <div style={{ fontSize: 11, color: "rgba(255,255,255,0.25)", fontFamily: "'IBM Plex Mono', monospace",
            marginBottom: 16, letterSpacing: "0.08em" }}>AGENT DETAILS</div>
          <div style={s.field}>
            <label style={s.label}>NAME</label>
            <input style={s.input} value={form.name} onChange={e => setForm(f => ({...f, name: e.target.value}))}
              placeholder="e.g. MacroBrief" />
          </div>
          <div style={s.field}>
            <label style={s.label}>DESCRIPTION</label>
            <textarea style={{ ...s.input, height: 72, resize: "none" } as React.CSSProperties}
              value={form.description} onChange={e => setForm(f => ({...f, description: e.target.value}))}
              placeholder="What does this agent do?" />
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
            <div style={s.field}>
              <label style={s.label}>FRAMEWORK</label>
              <select style={{ ...s.input, appearance: "none" }} value={form.framework}
                onChange={e => setForm(f => ({...f, framework: e.target.value}))}>
                {FRAMEWORKS.map(fw => <option key={fw} value={fw}>{fw}</option>)}
              </select>
            </div>
            <div style={s.field}>
              <label style={s.label}>OWNER EMAIL</label>
              <input style={s.input} type="email" value={form.owner_email}
                onChange={e => setForm(f => ({...f, owner_email: e.target.value}))}
                placeholder="you@example.com" />
            </div>
          </div>
          <div style={s.field}>
            <label style={s.label}>WEBHOOK URL</label>
            <input style={s.input} value={form.webhook_url}
              onChange={e => setForm(f => ({...f, webhook_url: e.target.value}))}
              placeholder="https://your-agent.io/agentmesh/webhook" />
          </div>
        </div>

        {/* Tool selector */}
        {(["has", "needs"] as const).map(field => (
          <div key={field} style={{ padding: "24px", borderRadius: 10, background: "rgba(255,255,255,0.02)",
            border: `1px solid ${field === "has" ? "rgba(99,235,165,0.1)" : "rgba(255,255,255,0.07)"}`,
            marginBottom: 16 }}>
            <div style={{ marginBottom: 16 }}>
              <div style={{ fontSize: 11, color: "rgba(255,255,255,0.25)", fontFamily: "'IBM Plex Mono', monospace",
                letterSpacing: "0.08em" }}>
                {field === "has" ? "TOOLS THIS AGENT HAS" : "TOOLS THIS AGENT NEEDS"}
              </div>
              <div style={{ fontSize: 12, color: "rgba(255,255,255,0.3)", marginTop: 3 }}>
                {field === "has" ? "APIs and tools your agent can call" : "Capabilities you want from matched agents"}
              </div>
            </div>
            {Object.entries(grouped).map(([cat, tools]) => (
              <div key={cat} style={{ marginBottom: 14 }}>
                <div style={{ fontSize: 10, color: "rgba(255,255,255,0.2)", fontFamily: "'IBM Plex Mono', monospace",
                  letterSpacing: "0.08em", marginBottom: 6 }}>{cat.toUpperCase()}</div>
                <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
                  {tools.map(tool => {
                    const active = form[field].includes(tool.id);
                    return (
                      <button key={tool.id} onClick={() => toggleTool(field, tool.id)} style={{
                        padding: "4px 10px", borderRadius: 5, fontSize: 12, fontWeight: 500,
                        fontFamily: "'IBM Plex Mono', monospace",
                        background: active ? "rgba(99,235,165,0.12)" : "rgba(255,255,255,0.04)",
                        border: `1px solid ${active ? "rgba(99,235,165,0.3)" : "rgba(255,255,255,0.08)"}`,
                        color: active ? "rgba(99,235,165,0.9)" : "rgba(255,255,255,0.4)",
                        transition: "all 0.1s",
                        display: "flex", alignItems: "center", gap: 5,
                      }}>
                        {tool.color && (
                          <span style={{ width: 6, height: 6, borderRadius: "50%",
                            background: active ? tool.color : "rgba(255,255,255,0.15)", flexShrink: 0 }} />
                        )}
                        {tool.label}
                      </button>
                    );
                  })}
                </div>
              </div>
            ))}
            {form[field].length > 0 && (
              <div style={{ marginTop: 8, fontSize: 11, color: "rgba(99,235,165,0.5)", fontFamily: "'IBM Plex Mono', monospace" }}>
                {form[field].length} selected: {form[field].join(", ")}
              </div>
            )}
          </div>
        ))}

        {error && (
          <div style={{ padding: "12px 16px", borderRadius: 6, background: "rgba(239,68,68,0.08)",
            border: "1px solid rgba(239,68,68,0.2)", color: "#ef4444", fontSize: 13, marginBottom: 16 }}>
            {error}
          </div>
        )}

        <button onClick={submit} disabled={loading || !form.name || !form.owner_email || !form.webhook_url} style={{
          width: "100%", padding: "14px", borderRadius: 8, fontSize: 15, fontWeight: 600,
          background: "rgba(99,235,165,0.12)", border: "1px solid rgba(99,235,165,0.3)",
          color: "rgba(99,235,165,0.9)", opacity: loading ? 0.6 : 1,
        }}>
          {loading ? "Registering..." : "Register agent →"}
        </button>
      </div>
    </div>
  );
}
