-- ═══════════════════════════════════════════════════
-- AgentMesh v0.4 Migration
-- Features: Health Monitoring, Agent Versioning,
--           Marketplace, Orgs, SLA, MCP, Streaming, Analytics
-- ═══════════════════════════════════════════════════

-- ─── Health Monitoring ─────────────────────────────

CREATE TABLE IF NOT EXISTS agent_health_checks (
  id          TEXT PRIMARY KEY DEFAULT 'hc_' || replace(gen_random_uuid()::text, '-', ''),
  agent_id    TEXT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
  status      TEXT NOT NULL CHECK (status IN ('online','idle','offline')),
  latency_ms  INTEGER,
  checked_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_health_agent_time ON agent_health_checks(agent_id, checked_at DESC);

-- ─── Agent Versioning ──────────────────────────────

CREATE TABLE IF NOT EXISTS agent_versions (
  id          TEXT PRIMARY KEY DEFAULT 'av_' || replace(gen_random_uuid()::text, '-', ''),
  agent_id    TEXT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
  version     INTEGER NOT NULL,
  has         TEXT[] NOT NULL,
  needs       TEXT[] NOT NULL,
  description TEXT NOT NULL,
  changelog   TEXT,
  created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_version ON agent_versions(agent_id, version);
CREATE INDEX IF NOT EXISTS idx_agent_versions_agent ON agent_versions(agent_id, version DESC);

ALTER TABLE matches ADD COLUMN IF NOT EXISTS agent_a_version INTEGER DEFAULT 1;
ALTER TABLE matches ADD COLUMN IF NOT EXISTS agent_b_version INTEGER DEFAULT 1;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS current_version INTEGER DEFAULT 1;

-- ─── Marketplace ───────────────────────────────────

ALTER TABLE agents ADD COLUMN IF NOT EXISTS marketplace_listed BOOLEAN DEFAULT FALSE;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS marketplace_tagline TEXT;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS marketplace_categories TEXT[] DEFAULT '{}';

CREATE TABLE IF NOT EXISTS marketplace_reviews (
  id          TEXT PRIMARY KEY DEFAULT 'mr_' || replace(gen_random_uuid()::text, '-', ''),
  agent_id    TEXT NOT NULL REFERENCES agents(id),
  reviewer_agent_id TEXT NOT NULL REFERENCES agents(id),
  rating      INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
  comment     TEXT,
  delegation_id TEXT REFERENCES delegations(id),
  created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_reviews_agent ON marketplace_reviews(agent_id);

CREATE TABLE IF NOT EXISTS platform_fees (
  id                    TEXT PRIMARY KEY DEFAULT 'pf_' || replace(gen_random_uuid()::text, '-', ''),
  billing_transaction_id TEXT NOT NULL REFERENCES billing_transactions(id),
  fee_cents             INTEGER NOT NULL,
  currency              TEXT DEFAULT 'usd',
  created_at            TIMESTAMPTZ DEFAULT NOW()
);

-- ─── Organizations ─────────────────────────────────

CREATE TABLE IF NOT EXISTS orgs (
  id           TEXT PRIMARY KEY DEFAULT 'org_' || replace(gen_random_uuid()::text, '-', ''),
  name         TEXT NOT NULL,
  slug         TEXT NOT NULL UNIQUE,
  plan         TEXT DEFAULT 'free' CHECK (plan IN ('free','pro','enterprise')),
  stripe_customer_id TEXT,
  created_at   TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS org_members (
  org_id     TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  email      TEXT NOT NULL,
  role       TEXT NOT NULL CHECK (role IN ('owner','admin','member','viewer')),
  invited_at TIMESTAMPTZ DEFAULT NOW(),
  joined_at  TIMESTAMPTZ,
  PRIMARY KEY (org_id, email)
);

CREATE TABLE IF NOT EXISTS org_api_keys (
  id           TEXT PRIMARY KEY DEFAULT 'oak_' || replace(gen_random_uuid()::text, '-', ''),
  org_id       TEXT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  name         TEXT NOT NULL,
  api_key_hash TEXT NOT NULL,
  scopes       TEXT[] DEFAULT '{}',
  created_at   TIMESTAMPTZ DEFAULT NOW(),
  last_used_at TIMESTAMPTZ
);

ALTER TABLE agents ADD COLUMN IF NOT EXISTS org_id TEXT REFERENCES orgs(id);
CREATE INDEX IF NOT EXISTS idx_agents_org ON agents(org_id);

-- ─── SLA Contracts ─────────────────────────────────

ALTER TABLE matches ADD COLUMN IF NOT EXISTS sla JSONB;

CREATE TABLE IF NOT EXISTS sla_violations (
  id          TEXT PRIMARY KEY DEFAULT 'sv_' || replace(gen_random_uuid()::text, '-', ''),
  match_id    TEXT NOT NULL REFERENCES matches(id),
  violating_agent_id TEXT NOT NULL REFERENCES agents(id),
  violation_type TEXT NOT NULL CHECK (violation_type IN ('uptime','latency','failure_rate')),
  measured_value DECIMAL NOT NULL,
  threshold      DECIMAL NOT NULL,
  window_start   TIMESTAMPTZ NOT NULL,
  window_end     TIMESTAMPTZ NOT NULL,
  resolved       BOOLEAN DEFAULT FALSE,
  created_at     TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sla_violations_match ON sla_violations(match_id, created_at DESC);

-- ─── MCP Server Registry ──────────────────────────

CREATE TABLE IF NOT EXISTS mcp_servers (
  id          TEXT PRIMARY KEY DEFAULT 'mcp_' || replace(gen_random_uuid()::text, '-', ''),
  agent_id    TEXT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  url         TEXT NOT NULL,
  auth_type   TEXT DEFAULT 'none' CHECK (auth_type IN ('none','bearer','api_key')),
  auth_secret TEXT,
  tools       JSONB DEFAULT '[]'::jsonb,
  last_synced TIMESTAMPTZ,
  created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_mcp_agent ON mcp_servers(agent_id);

-- ─── Streaming Delegations ────────────────────────

ALTER TABLE delegations ADD COLUMN IF NOT EXISTS streaming BOOLEAN DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS delegation_events (
  id            TEXT PRIMARY KEY DEFAULT 'de_' || replace(gen_random_uuid()::text, '-', ''),
  delegation_id TEXT NOT NULL REFERENCES delegations(id) ON DELETE CASCADE,
  sequence      INTEGER NOT NULL,
  event_type    TEXT NOT NULL CHECK (event_type IN ('progress','partial_output','error','completed')),
  data          JSONB NOT NULL,
  created_at    TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_events_delegation ON delegation_events(delegation_id, sequence ASC);
