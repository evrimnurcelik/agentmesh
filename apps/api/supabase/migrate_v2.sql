-- ═══════════════════════════════════════════════════════════════
-- AgentMesh Migration: v0.2 + v0.3
-- Run in Supabase SQL editor after initial schema.sql
-- ═══════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────
-- v0.2: CAPABILITY NEGOTIATION
-- Add input/output schemas to capabilities
-- ─────────────────────────────────────────
ALTER TABLE capabilities
  ADD COLUMN IF NOT EXISTS input_schema  JSONB DEFAULT '{}'::jsonb,
  ADD COLUMN IF NOT EXISTS output_schema JSONB DEFAULT '{}'::jsonb;

-- Populate schemas for existing capabilities
UPDATE capabilities SET
  input_schema  = '{"channel":{"type":"string","required":true},"text":{"type":"string","required":true},"workspace":{"type":"string"}}'::jsonb,
  output_schema = '{"status":{"type":"string"},"message_id":{"type":"string"},"timestamp":{"type":"string"}}'::jsonb
WHERE id = 'slack';

UPDATE capabilities SET
  input_schema  = '{"to":{"type":"string","required":true},"subject":{"type":"string","required":true},"body":{"type":"string","required":true}}'::jsonb,
  output_schema = '{"status":{"type":"string"},"message_id":{"type":"string"}}'::jsonb
WHERE id = 'gmail';

UPDATE capabilities SET
  input_schema  = '{"symbol":{"type":"string","required":true}}'::jsonb,
  output_schema = '{"symbol":{"type":"string"},"price":{"type":"number"},"change_pct":{"type":"number"},"volume":{"type":"integer"}}'::jsonb
WHERE id = 'yahoo_finance';

UPDATE capabilities SET
  input_schema  = '{"series_id":{"type":"string","required":true},"start_date":{"type":"string"},"end_date":{"type":"string"}}'::jsonb,
  output_schema = '{"series_id":{"type":"string"},"data":{"type":"array"},"units":{"type":"string"}}'::jsonb
WHERE id = 'fred_api';

UPDATE capabilities SET
  input_schema  = '{"query":{"type":"string","required":true},"limit":{"type":"integer"}}'::jsonb,
  output_schema = '{"results":{"type":"array"},"total":{"type":"integer"}}'::jsonb
WHERE id = 'web_search';

UPDATE capabilities SET
  input_schema  = '{"sheet_id":{"type":"string","required":true},"range":{"type":"string","required":true},"values":{"type":"array","required":true}}'::jsonb,
  output_schema = '{"updated_range":{"type":"string"},"updated_rows":{"type":"integer"}}'::jsonb
WHERE id = 'google_sheets';

UPDATE capabilities SET
  input_schema  = '{"title":{"type":"string","required":true},"content":{"type":"string"},"parent_id":{"type":"string"}}'::jsonb,
  output_schema = '{"page_id":{"type":"string"},"url":{"type":"string"}}'::jsonb
WHERE id = 'notion';

UPDATE capabilities SET
  input_schema  = '{"prompt":{"type":"string","required":true},"model":{"type":"string"},"max_tokens":{"type":"integer"}}'::jsonb,
  output_schema = '{"text":{"type":"string"},"input_tokens":{"type":"integer"},"output_tokens":{"type":"integer"}}'::jsonb
WHERE id = 'anthropic';

-- ─────────────────────────────────────────
-- v0.2: TRUST TIERS
-- Add trust_tier to agents
-- ─────────────────────────────────────────
ALTER TABLE agents
  ADD COLUMN IF NOT EXISTS trust_tier TEXT DEFAULT 'public'
    CHECK (trust_tier IN ('public','verified','private')),
  ADD COLUMN IF NOT EXISTS verified_at  TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS stripe_account_id TEXT;  -- for v0.3 billing

CREATE INDEX IF NOT EXISTS idx_agents_trust ON agents(trust_tier, public);

-- ─────────────────────────────────────────
-- v0.2: DELEGATION CHAINS
-- Add chain tracking to delegations
-- ─────────────────────────────────────────
ALTER TABLE delegations
  ADD COLUMN IF NOT EXISTS chain_id       TEXT,           -- shared across all hops in a chain
  ADD COLUMN IF NOT EXISTS chain_depth    INTEGER DEFAULT 0,
  ADD COLUMN IF NOT EXISTS parent_delegation_id TEXT REFERENCES delegations(id),
  ADD COLUMN IF NOT EXISTS fallback_agent_id    TEXT REFERENCES agents(id),
  ADD COLUMN IF NOT EXISTS fallback_triggered   BOOLEAN DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_delegations_chain ON delegations(chain_id);

-- ─────────────────────────────────────────
-- v0.3: AGENT TEAMS
-- ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS teams (
  id          TEXT PRIMARY KEY DEFAULT 'tm_' || replace(gen_random_uuid()::text, '-', ''),
  name        TEXT NOT NULL,
  description TEXT NOT NULL,
  owner_email TEXT NOT NULL,
  api_key_hash TEXT NOT NULL,        -- team-level API key for acting as a unit
  public      BOOLEAN DEFAULT TRUE,
  has         TEXT[] DEFAULT '{}',   -- union of all member has[] (computed)
  needs       TEXT[] DEFAULT '{}',   -- union of all member needs[] (computed)
  stats       JSONB DEFAULT '{"delegations_sent":0,"delegations_received":0}'::jsonb,
  created_at  TIMESTAMPTZ DEFAULT NOW(),
  updated_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS team_members (
  team_id     TEXT NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
  agent_id    TEXT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
  role        TEXT DEFAULT 'member' CHECK (role IN ('lead','member')),
  added_at    TIMESTAMPTZ DEFAULT NOW(),
  PRIMARY KEY (team_id, agent_id)
);

CREATE INDEX IF NOT EXISTS idx_team_members_agent ON team_members(agent_id);

-- ─────────────────────────────────────────
-- v0.3: BILLING / STRIPE
-- Per-delegation micropayment records
-- ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS billing_rates (
  agent_id    TEXT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
  task_type   TEXT NOT NULL,
  price_cents INTEGER NOT NULL DEFAULT 0,   -- USD cents per delegation
  currency    TEXT DEFAULT 'usd',
  active      BOOLEAN DEFAULT TRUE,
  created_at  TIMESTAMPTZ DEFAULT NOW(),
  PRIMARY KEY (agent_id, task_type)
);

CREATE TABLE IF NOT EXISTS billing_transactions (
  id              TEXT PRIMARY KEY DEFAULT 'bt_' || replace(gen_random_uuid()::text, '-', ''),
  delegation_id   TEXT NOT NULL REFERENCES delegations(id),
  payer_agent_id  TEXT NOT NULL REFERENCES agents(id),
  payee_agent_id  TEXT NOT NULL REFERENCES agents(id),
  amount_cents    INTEGER NOT NULL,
  currency        TEXT DEFAULT 'usd',
  stripe_payment_intent_id TEXT,
  status          TEXT DEFAULT 'pending' CHECK (status IN ('pending','completed','failed','refunded')),
  created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_billing_delegation  ON billing_transactions(delegation_id);
CREATE INDEX IF NOT EXISTS idx_billing_payer       ON billing_transactions(payer_agent_id);
CREATE INDEX IF NOT EXISTS idx_billing_payee       ON billing_transactions(payee_agent_id);

-- RLS for new tables
ALTER TABLE teams                  ENABLE ROW LEVEL SECURITY;
ALTER TABLE team_members           ENABLE ROW LEVEL SECURITY;
ALTER TABLE billing_rates          ENABLE ROW LEVEL SECURITY;
ALTER TABLE billing_transactions   ENABLE ROW LEVEL SECURITY;

CREATE POLICY "teams_public_read"  ON teams FOR SELECT USING (public = true);

-- Updated_at trigger for teams
CREATE TRIGGER teams_updated_at BEFORE UPDATE ON teams
  FOR EACH ROW EXECUTE FUNCTION update_updated_at();
