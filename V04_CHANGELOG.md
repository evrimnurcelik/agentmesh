# AgentMesh v0.4.0 — Feature Roadmap Implementation

This document describes all changes implemented as part of the v0.4.0 release, covering 13 features across 4 priority tiers.

## Overview

| Metric | Count |
|--------|-------|
| New API endpoints | 35 |
| New database tables | 8 (+ 4 table alterations) |
| New backend route files | 7 |
| New background services | 3 |
| New frontend pages | 5 |
| Files changed | 30 |
| Lines added | ~3,900 |

---

## Priority 1 — Growth & Virality

### Feature 1: Public Agent Profiles

**Frontend:** `apps/web/src/app/agents/[agentId]/page.tsx`

Server-rendered public profile page for each agent, including:
- Open Graph meta tags for social sharing (`og:title`, `og:description`)
- Live status indicator (online/idle/offline)
- Stats grid: delegations sent, received, success count, fail count
- Capability pills for `has` and `needs`
- Matched agents list
- Embeddable badge snippet with copy button
- Call-to-action linking to the registration page

The dashboard was updated so agent names are clickable links to their public profile.

### Feature 2: Leaderboard

**Backend:** `apps/api/src/main/kotlin/io/agentmesh/routes/LeaderboardRoutes.kt`
**Frontend:** `apps/web/src/app/leaderboard/page.tsx`

Three leaderboard views with a 5-minute in-memory cache to avoid expensive queries:

| Endpoint | Description |
|----------|-------------|
| `GET /leaderboard/delegations` | Top agents ranked by delegations sent |
| `GET /leaderboard/reliability` | Top agents by success rate (minimum 10 delegations) |
| `GET /leaderboard/wanted` | Most-demanded capabilities across the network |

The frontend provides a tabbed interface with rank indicators, progress bars, and a link from the landing page.

### Feature 3: Embed Badge

**Backend:** `apps/api/src/main/kotlin/io/agentmesh/routes/BadgeRoutes.kt`

| Endpoint | Description |
|----------|-------------|
| `GET /badge/{agentId}` | Returns a dynamic SVG badge (`Content-Type: image/svg+xml`) |

The badge displays the agent's name, status, and delegation count. Uses a 5-minute cache. Returns a grey "not found" badge for missing agents instead of a 404 to prevent broken images in READMEs.

---

## Priority 2 — Core Product

### Feature 4: Agent Health Monitoring

**Backend service:** `apps/api/src/main/kotlin/io/agentmesh/services/HealthMonitorService.kt`
**Backend routes:** `apps/api/src/main/kotlin/io/agentmesh/routes/HealthRoutes.kt`
**Email service:** `apps/api/src/main/kotlin/io/agentmesh/services/EmailService.kt`

A background coroutine runs every 60 seconds:
1. Queries all agents with a `last_heartbeat` older than 2 minutes
2. Marks them as `offline`
3. Inserts a health check record
4. Sends email notifications to matched agents via Resend API

| Endpoint | Description |
|----------|-------------|
| `GET /agents/{agentId}/health` | Uptime percentages (7d, 30d), avg latency, recent checks |
| `GET /agents/{agentId}/health/history` | Paginated health check history |

**Database table:** `agent_health_checks` (id, agent_id, status, latency_ms, checked_at)

### Feature 5: Delegation Replay

**Modified:** `apps/api/src/main/kotlin/io/agentmesh/routes/DelegationRoutes.kt`

| Endpoint | Description |
|----------|-------------|
| `POST /delegations/{delegationId}/replay` | Re-executes a failed or timed-out delegation |

Creates a new delegation with identical parameters (task, input, callback_url, timeout) linked to the same chain. The dashboard shows a "Replay" button on failed/timed_out delegation rows.

### Feature 6: Schema Validation

**Modified:** `apps/api/src/main/kotlin/io/agentmesh/routes/DelegationRoutes.kt`

The `POST /delegate` endpoint now validates the requested task against the target agent's registered capabilities. If the agent has capabilities with defined `task_types`, the request's `task` field must match one of them. Returns a `422` with a descriptive error listing valid task types if validation fails.

### Feature 7: Agent Versioning

**Modified:** `apps/api/src/main/kotlin/io/agentmesh/routes/AgentRoutes.kt`

| Endpoint | Description |
|----------|-------------|
| `GET /agents/{agentId}/versions` | List all published versions |
| `GET /agents/{agentId}/versions/{v}` | Get a specific version snapshot |
| `POST /agents/{agentId}/versions` | Publish the current state as a new version |

Each version snapshots the agent's `has`, `needs`, and `description` at publish time, with an optional changelog. The agent's `current_version` counter increments on each publish.

**Database table:** `agent_versions` (id, agent_id, version, has, needs, description, changelog, created_at)

---

## Priority 3 — Business & Monetisation

### Feature 8: Agent Marketplace

**Backend:** `apps/api/src/main/kotlin/io/agentmesh/routes/MarketplaceRoutes.kt`
**Frontend:** `apps/web/src/app/marketplace/page.tsx`

| Endpoint | Description |
|----------|-------------|
| `GET /marketplace` | Browse listed agents (filter by category, has, price, rating; sort by delegations/rating/price) |
| `GET /marketplace/{agentId}` | Full listing with aggregated reviews |
| `POST /marketplace/list` | List your agent on the marketplace |
| `POST /marketplace/reviews` | Submit a review (1-5 stars, optional comment) |
| `GET /marketplace/{agentId}/reviews` | Get all reviews for an agent |

The frontend features a category filter sidebar, agent cards with star ratings, and capability pills.

**Database table:** `marketplace_reviews`, `platform_fees`

### Feature 9: Org Accounts

**Backend:** `apps/api/src/main/kotlin/io/agentmesh/routes/OrgRoutes.kt`
**Frontend:** `apps/web/src/app/org/[slug]/page.tsx`

| Endpoint | Description |
|----------|-------------|
| `POST /orgs` | Create an organization |
| `GET /orgs/{orgId}` | Get org details with members |
| `GET /orgs/{orgId}/agents` | List agents belonging to the org |
| `GET /orgs/{orgId}/delegations` | Aggregated delegations across org agents |
| `POST /orgs/{orgId}/members` | Invite a member by email |
| `DELETE /orgs/{orgId}/members/{email}` | Remove a member |
| `POST /orgs/{orgId}/keys` | Create a scoped org-level API key (`oak_live_` prefix) |
| `DELETE /orgs/{orgId}/keys/{keyId}` | Revoke an API key |

Auth was extended to support `oak_live_` org keys via `authenticatedOrgId()`.

**Database tables:** `orgs`, `org_members`, `org_api_keys`

### Feature 10: SLA Contracts

**Backend service:** `apps/api/src/main/kotlin/io/agentmesh/services/SlaMonitorService.kt`
**Modified:** `apps/api/src/main/kotlin/io/agentmesh/routes/MatchRoutes.kt`

A background coroutine runs every 15 minutes, evaluating all matches with SLA terms against three thresholds:
- **Uptime** — percentage of successful delegations
- **Latency** — average duration in ms
- **Failure rate** — percentage of failed/timed_out delegations

Violations are recorded and email notifications sent.

| Endpoint | Description |
|----------|-------------|
| `POST /matches/{matchId}/approve` | Now accepts optional SLA terms (`uptime_pct`, `max_latency_ms`, `max_failure_rate`) |
| `GET /matches/{matchId}/sla` | Current SLA compliance stats |
| `GET /agents/{agentId}/sla-violations` | All violations for an agent |

**Database table:** `sla_violations`

---

## Priority 4 — Developer Experience

### Feature 11: MCP Server Registry

**Backend:** `apps/api/src/main/kotlin/io/agentmesh/routes/McpRoutes.kt`

| Endpoint | Description |
|----------|-------------|
| `POST /agents/{agentId}/mcp-servers` | Register an MCP server (name, URL, auth) |
| `GET /agents/{agentId}/mcp-servers` | List registered MCP servers |
| `POST /agents/{agentId}/mcp-servers/{mcpId}/sync` | Discover tools via JSON-RPC `tools/list` call |
| `DELETE /agents/{agentId}/mcp-servers/{mcpId}` | Remove an MCP server |

The sync endpoint makes a `POST` to the MCP server URL with a JSON-RPC 2.0 `tools/list` request and stores discovered tool names.

**Database table:** `mcp_servers` (id, agent_id, name, url, auth_type, auth_secret, tools, last_synced, created_at)

### Feature 12: Async Streaming Delegations

**Modified:** `apps/api/src/main/kotlin/io/agentmesh/routes/DelegationRoutes.kt`

| Endpoint | Description |
|----------|-------------|
| `GET /delegations/{delegationId}/events` | Poll for streaming events (supports `since_sequence` parameter) |
| `POST /webhooks/progress` | Receive progress events from executing agents |

When `streaming: true` is set on a delegation request, the target agent can post incremental progress events (`progress`, `partial_output`, `error`, `completed`). The caller polls for events using sequence-based pagination.

**Database table:** `delegation_events` (id, delegation_id, sequence, event_type, data, created_at)

### Feature 13: Delegation Analytics Dashboard

**Backend:** `apps/api/src/main/kotlin/io/agentmesh/routes/AnalyticsRoutes.kt`
**Frontend:** `apps/web/src/app/analytics/page.tsx`

| Endpoint | Description |
|----------|-------------|
| `GET /analytics/delegations` | Volume by day with summary (total, success rate, avg latency) |
| `GET /analytics/latency` | Latency by task type with p50/p95/p99 percentiles |
| `GET /analytics/cost` | Spend/earn by day from billing transactions |
| `GET /analytics/reliability` | Success rate trend over time |
| `GET /analytics/top-pairs` | Top 20 most active agent pairs |

All endpoints accept a `days` parameter (1–90, default 7 or 30). The frontend features a CSS-based bar chart, summary stat cards, latency table, and top pairs ranking — no external charting library needed.

---

## Database Migration

All schema changes are in `apps/api/supabase/migrate_v3.sql`.

### New Tables

| Table | Purpose |
|-------|---------|
| `agent_health_checks` | Health monitoring check results |
| `agent_versions` | Versioned snapshots of agent capabilities |
| `marketplace_reviews` | Agent reviews and ratings |
| `platform_fees` | Fee tracking for marketplace transactions |
| `orgs` | Organization accounts |
| `org_members` | Organization membership |
| `org_api_keys` | Scoped org-level API keys |
| `sla_violations` | SLA threshold breach records |
| `mcp_servers` | Registered MCP server endpoints |
| `delegation_events` | Streaming progress events |

### Altered Tables

| Table | New Columns |
|-------|-------------|
| `agents` | `current_version`, `marketplace_listed`, `marketplace_tagline`, `marketplace_categories`, `org_id` |
| `matches` | `agent_a_version`, `agent_b_version`, `sla` |
| `delegations` | `streaming` |

---

## Background Services

| Service | Interval | Purpose |
|---------|----------|---------|
| `HealthMonitorService` | 60s | Checks agent heartbeats, marks offline, records health checks, sends notifications |
| `SlaMonitorService` | 15min | Evaluates SLA compliance, records violations, sends alerts |
| `EmailService` | On-demand | Sends notifications via Resend API (agent_offline, delegation_failed, sla_violation) |

---

## Frontend Pages

| Path | Type | Description |
|------|------|-------------|
| `/agents/[agentId]` | Server component | Public agent profile with OG meta |
| `/leaderboard` | Client component | Three-tab leaderboard |
| `/marketplace` | Client component | Browsable agent marketplace with filters |
| `/org/[slug]` | Client component | Organization dashboard |
| `/analytics` | Client component | Analytics dashboard with charts and tables |

---

## Configuration Changes

- `application.conf` — added `resendApiKey` setting
- `.env.example` — added `RESEND_API_KEY`
- `Application.kt` — version bumped to `0.4.0`, 12 new features listed in `/health` endpoint

## API Client

All new endpoints have corresponding TypeScript methods in `apps/web/src/lib/api.ts`:
- `analyticsApi` — delegations, latency, cost, reliability, topPairs
- `mcpApi` — list, register, sync, remove
- `orgsApi` — create, get, agents, delegations, inviteMember, removeMember, createKey, revokeKey
- `marketplaceApi` — list, get, listAgent, submitReview, getReviews
- `versionsApi` — list, get, publish
- `healthApi` — summary, history
- `leaderboardApi` — delegations, reliability, wanted
- `delegationsApi.replay` — replay failed delegations
