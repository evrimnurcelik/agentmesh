# AgentMesh Feature Roadmap — Implementation Plan

## Overview

13 features across 4 priority tiers. Each feature is self-contained. We'll implement them in priority order, committing after each feature.

**Branch:** `claude/agentmesh-feature-roadmap-0DSOq`

---

## Phase 1: Priority 1 — Growth & Virality (Features 1-3)

### Step 1.1: Public Agent Profiles (Feature 1)

**Files to create:**
- `apps/web/src/app/agents/[agentId]/page.tsx` — Server component showing agent profile

**Files to modify:**
- `apps/web/src/app/dashboard/page.tsx` — Make agent names clickable links to `/agents/[id]`

**No backend changes needed** — `GET /agents/{agentId}` and `GET /capabilities` already exist.

**Details:**
- Server component with `fetch` (no-store cache)
- Sections: header with badges, stats bar, has/needs pills, matched agents links, CTA button
- OG meta tags for social sharing
- Copy-link button

### Step 1.2: Leaderboard (Feature 2)

**Files to create:**
- `apps/api/src/main/kotlin/io/agentmesh/routes/LeaderboardRoutes.kt` — 3 leaderboard endpoints
- `apps/web/src/app/leaderboard/page.tsx` — Client component with 3 tabs

**Files to modify:**
- `apps/api/src/main/kotlin/io/agentmesh/Application.kt` — Register `leaderboardRoutes()`
- `apps/web/src/lib/api.ts` — Add leaderboard API methods
- `apps/web/src/app/layout.tsx` — Add nav bar with leaderboard link
- `apps/web/src/app/page.tsx` — Add "See the leaderboard" link

**API endpoints:**
- `GET /leaderboard/delegations` — top by delegations_sent
- `GET /leaderboard/reliability` — top by success rate (min 10 total)
- `GET /leaderboard/wanted` — most-wanted capabilities

Each cached for 5 minutes using in-memory map.

### Step 1.3: Embed Badge (Feature 3)

**Files to create:**
- `apps/api/src/main/kotlin/io/agentmesh/routes/BadgeRoutes.kt` — SVG badge endpoint

**Files to modify:**
- `apps/api/src/main/kotlin/io/agentmesh/Application.kt` — Register `badgeRoutes()`
- `apps/web/src/app/agents/[agentId]/page.tsx` — Add "Embed this badge" section

**API endpoint:**
- `GET /badge/{agentId}` — Returns SVG with Content-Type image/svg+xml, 5-min cache

---

## Phase 2: Priority 2 — Core Product (Features 4-7)

### Step 2.1: Agent Health Monitoring (Feature 4)

**Files to create:**
- `apps/api/supabase/migrate_v3.sql` — New tables: `agent_health_checks`
- `apps/api/src/main/kotlin/io/agentmesh/routes/HealthRoutes.kt` — Health endpoints
- `apps/api/src/main/kotlin/io/agentmesh/services/HealthMonitorService.kt` — Background job (60s interval)
- `apps/api/src/main/kotlin/io/agentmesh/services/EmailService.kt` — Resend API integration

**Files to modify:**
- `apps/api/src/main/kotlin/io/agentmesh/db/Tables.kt` — Add `AgentHealthChecks` table
- `apps/api/src/main/kotlin/io/agentmesh/db/DatabaseFactory.kt` — Register new table
- `apps/api/src/main/kotlin/io/agentmesh/Application.kt` — Start HealthMonitorService, register routes
- `apps/api/src/main/resources/application.conf` — Add `RESEND_API_KEY`
- `.env.example` — Add `RESEND_API_KEY`
- `apps/web/src/app/agents/[agentId]/page.tsx` — Add uptime badges, latency sparkline
- `apps/web/src/app/dashboard/page.tsx` — Add uptime % to agent cards
- `apps/web/src/lib/api.ts` — Add health API methods

**API endpoints:**
- `GET /agents/{agentId}/health` — uptime %, avg latency, last 50 checks
- `GET /agents/{agentId}/health/history` — paginated check history

### Step 2.2: Delegation Replay (Feature 5)

**Files to modify:**
- `apps/api/src/main/kotlin/io/agentmesh/routes/DelegationRoutes.kt` — Add `POST /delegations/{id}/replay`
- `apps/web/src/app/dashboard/page.tsx` — Add Replay button on failed/timed_out rows
- `apps/web/src/lib/api.ts` — Add `replay()` method

### Step 2.3: Schema Validation on Delegation (Feature 6)

**Files to modify:**
- `apps/api/src/main/kotlin/io/agentmesh/routes/DelegationRoutes.kt` — Add input validation against capability schema
- `apps/api/src/main/kotlin/io/agentmesh/models/Models.kt` — Add `ValidationError` data class
- `apps/web/src/app/dashboard/page.tsx` — Show validation errors in expanded delegation rows

### Step 2.4: Agent Versioning (Feature 7)

**Files to create (in migrate_v3.sql, appended):**
- `agent_versions` table
- ALTER `matches` to add version columns
- ALTER `agents` to add `current_version`

**Files to create:**
- (Routes added to AgentRoutes.kt)

**Files to modify:**
- `apps/api/src/main/kotlin/io/agentmesh/db/Tables.kt` — Add `AgentVersions` table, version columns
- `apps/api/src/main/kotlin/io/agentmesh/db/DatabaseFactory.kt` — Register `AgentVersions`
- `apps/api/src/main/kotlin/io/agentmesh/routes/AgentRoutes.kt` — Add version endpoints, update PATCH response
- `apps/api/src/main/kotlin/io/agentmesh/models/Models.kt` — Add version models
- `apps/web/src/app/dashboard/page.tsx` — Add "Publish version" button
- `apps/web/src/app/agents/[agentId]/page.tsx` — Show version info
- `apps/web/src/lib/api.ts` — Add version API methods

---

## Phase 3: Priority 3 — Business & Monetisation (Features 8-10)

### Step 3.1: Agent Marketplace (Feature 8)

**Files to create:**
- `apps/api/src/main/kotlin/io/agentmesh/routes/MarketplaceRoutes.kt`
- `apps/web/src/app/marketplace/page.tsx`

**Files to modify (in migrate_v3.sql, appended):**
- ALTER `agents` for marketplace columns
- `marketplace_reviews` table
- `platform_fees` table

**Files to modify:**
- `apps/api/src/main/kotlin/io/agentmesh/db/Tables.kt` — Add marketplace tables
- `apps/api/src/main/kotlin/io/agentmesh/db/DatabaseFactory.kt` — Register new tables
- `apps/api/src/main/kotlin/io/agentmesh/Application.kt` — Register routes
- `apps/api/src/main/kotlin/io/agentmesh/models/Models.kt` — Add marketplace models
- `apps/api/src/main/kotlin/io/agentmesh/routes/BillingRoutes.kt` — Platform fee logic
- `apps/web/src/lib/api.ts` — Marketplace API methods
- `apps/web/src/app/layout.tsx` — Add Marketplace nav link

### Step 3.2: Org Accounts (Feature 9)

**Files to create:**
- `apps/api/src/main/kotlin/io/agentmesh/routes/OrgRoutes.kt`
- `apps/web/src/app/org/[slug]/page.tsx`

**Files to modify (in migrate_v3.sql, appended):**
- `orgs`, `org_members`, `org_api_keys` tables
- ALTER `agents` add `org_id`

**Files to modify:**
- `apps/api/src/main/kotlin/io/agentmesh/db/Tables.kt` — Org tables
- `apps/api/src/main/kotlin/io/agentmesh/db/DatabaseFactory.kt`
- `apps/api/src/main/kotlin/io/agentmesh/Application.kt`
- `apps/api/src/main/kotlin/io/agentmesh/util/Auth.kt` — Support `oak_` prefix org keys
- `apps/api/src/main/kotlin/io/agentmesh/models/Models.kt`
- `apps/web/src/lib/api.ts`

### Step 3.3: SLA Contracts (Feature 10)

**Files to create:**
- `apps/api/src/main/kotlin/io/agentmesh/services/SlaMonitorService.kt`

**Files to modify (in migrate_v3.sql, appended):**
- ALTER `matches` add `sla` JSONB
- `sla_violations` table

**Files to modify:**
- `apps/api/src/main/kotlin/io/agentmesh/db/Tables.kt` — SLA tables
- `apps/api/src/main/kotlin/io/agentmesh/db/DatabaseFactory.kt`
- `apps/api/src/main/kotlin/io/agentmesh/Application.kt` — Start SlaMonitorService
- `apps/api/src/main/kotlin/io/agentmesh/routes/MatchRoutes.kt` — SLA on approve
- `apps/api/src/main/kotlin/io/agentmesh/models/Models.kt` — SLA models
- `apps/web/src/app/dashboard/page.tsx` — SLA config in match approval, compliance indicators
- `apps/web/src/lib/api.ts` — SLA API methods

---

## Phase 4: Priority 4 — Developer Experience (Features 11-13)

### Step 4.1: MCP Server Registry (Feature 11)

**Files to create:**
- `apps/api/src/main/kotlin/io/agentmesh/routes/McpRoutes.kt`

**Files to modify (in migrate_v3.sql, appended):**
- `mcp_servers` table

**Files to modify:**
- `apps/api/src/main/kotlin/io/agentmesh/db/Tables.kt` — McpServers table
- `apps/api/src/main/kotlin/io/agentmesh/db/DatabaseFactory.kt`
- `apps/api/src/main/kotlin/io/agentmesh/Application.kt`
- `apps/api/src/main/kotlin/io/agentmesh/routes/CapabilityRoutes.kt` — Include MCP capabilities
- `apps/web/src/app/register/page.tsx` — MCP server input section
- `apps/web/src/app/agents/[agentId]/page.tsx` — Show MCP vs built-in tools
- `apps/web/src/lib/api.ts` — MCP API methods

### Step 4.2: Async Streaming Delegations (Feature 12)

**Files to modify (in migrate_v3.sql, appended):**
- ALTER `delegations` add `streaming`
- `delegation_events` table

**Files to modify:**
- `apps/api/src/main/kotlin/io/agentmesh/db/Tables.kt` — DelegationEvents table, streaming column
- `apps/api/src/main/kotlin/io/agentmesh/db/DatabaseFactory.kt`
- `apps/api/src/main/kotlin/io/agentmesh/routes/DelegationRoutes.kt` — streaming support, events endpoints, SSE
- `apps/api/src/main/kotlin/io/agentmesh/routes/WebhookRoutes.kt` — Progress webhook endpoint
- `apps/api/src/main/kotlin/io/agentmesh/models/Models.kt` — Event models
- `apps/web/src/app/dashboard/page.tsx` — Live event feed in expanded rows
- `apps/web/src/lib/api.ts` — Events API

### Step 4.3: Delegation Analytics Dashboard (Feature 13)

**Files to create:**
- `apps/api/src/main/kotlin/io/agentmesh/routes/AnalyticsRoutes.kt`
- `apps/web/src/app/analytics/page.tsx`

**Files to modify:**
- `apps/api/src/main/kotlin/io/agentmesh/Application.kt` — Register routes
- `apps/web/src/lib/api.ts` — Analytics API methods
- `apps/web/src/app/layout.tsx` — Add Analytics nav link
- `apps/web/package.json` — Add `recharts` dependency

---

## Execution Order

1. **Feature 1** — Public Agent Profiles (frontend only, quick win)
2. **Feature 2** — Leaderboard (1 new route file + 1 page)
3. **Feature 3** — Embed Badge (1 route + SVG)
4. **Feature 4** — Health Monitoring (new migration, services, routes)
5. **Feature 5** — Delegation Replay (small addition to existing route)
6. **Feature 6** — Schema Validation (small addition to delegation route)
7. **Feature 7** — Agent Versioning (new table, route additions)
8. **Feature 8** — Marketplace (new route + page)
9. **Feature 9** — Org Accounts (new route + page + auth changes)
10. **Feature 10** — SLA Contracts (new service + route changes)
11. **Feature 11** — MCP Server Registry (new route + MCP client)
12. **Feature 12** — Streaming Delegations (SSE + events table)
13. **Feature 13** — Analytics Dashboard (queries + recharts page)

Commit after each feature. Push after each phase.

---

## Key Patterns

- **New routes**: Create file in `routes/`, register in `Application.kt` inside `listOf("", "/v1").forEach`
- **New tables**: Add to `Tables.kt`, register in `DatabaseFactory.kt`, add SQL to `migrate_v3.sql`
- **New models**: Add to `Models.kt`
- **New pages**: Create in `apps/web/src/app/[route]/page.tsx`
- **API client**: Add methods to `apps/web/src/lib/api.ts`
- **Nav links**: Update `apps/web/src/app/layout.tsx`
- **Env vars**: Update `application.conf`, `.env.example`, `deploy.yml`
