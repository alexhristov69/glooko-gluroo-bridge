# Glooko Gluroo Bridge — AWS Migration

## Design Document

| Field | Value |
|-------|-------|
| **Author** | Principal Engineering |
| **Audience** | CTO / Engineering Leadership |
| **Date** | July 8, 2026 |
| **Status** | Draft for Review |
| **Version** | 1.0 |
| **Classification** | Internal / Confidential |

---

## 1. Executive Summary

This document proposes migrating the Glooko-to-Gluroo insulin and pump data sync from a standalone Android client to a **serverless AWS backend**. The Android app remains the configuration and control surface (Save, Test, Sync Now), but all sync execution, scheduling, deduplication state, and credential storage move to the cloud.

The design optimizes for three constraints:

1. **Minimal operational cost** — target ~$2–3/month at 10 active bridges
2. **Multi-tenant isolation** — many independent bridges on a single AWS stack
3. **Hard cost guardrails** — cap worst-case spend in the low single-digit dollars per day, even under failure conditions

**Recommendation:** Proceed with a phased rollout using AWS SAM, Python Lambda, Step Functions, DynamoDB, Cognito, and SSM Parameter Store. No always-on compute, no VPC/NAT, no per-user Secrets Manager entries.

---

## 2. Background

### 2.1 What the system does

Glooko Gluroo Bridge is an Android application that reads bolus and pump data from [Glooko](https://www.glooko.com/) (via an unofficial browser-login API ported from [glooko-reader](https://github.com/spamsch/glooko-reader)) and publishes Nightscout-compatible treatments to [Gluroo](https://gluroo.com/) Global Connect.

| Source (Glooko) | Destination (Gluroo / Nightscout) |
|-----------------|-----------------------------------|
| Bolus deliveries | `Correction Bolus` treatments |
| Carbs at bolus time | `carbs` field on treatment |
| IOB / CGM context at bolus | Optional `Note` treatment |
| Pump mode statistics (Omnipod 5) | Optional `Note` treatment |
| **CGM glucose values** | **Not uploaded** (by design) |

### 2.2 Current architecture

Today the entire pipeline runs on-device:

- **Credentials:** Android `EncryptedSharedPreferences`
- **Deduplication:** Room SQLite (`synced_records`, `sync_state`)
- **Scheduling:** WorkManager (1–240 min interval, default 15 min)
- **Execution:** `SyncRepository` orchestrates `GlookoClient` → `GlookoParser` → `TreatmentMapper` → `NightscoutClient`

There is no backend, no cloud infrastructure, and no CI/CD pipeline.

### 2.3 Why change

| Limitation | Impact |
|------------|--------|
| Phone-dependent scheduling | Sync stops when device is off or app is killed |
| On-device credentials | Secrets on a personal device; no centralized rotation |
| No centralized observability | Cannot query run history or debug failures remotely |
| App release required for sync logic changes | Slow iteration on Glooko API adaptations |

---

## 3. Goals and Non-Goals

### 3.1 Goals

- Port sync/import execution to AWS while **preserving the existing Android configuration UI** (all settings fields, Test and Sync Now buttons)
- Support **multi-tenant** operation: many independent bridges on one AWS deployment
- Run **scheduled sync in AWS only** — phone not required for background sync
- Provide **workflow visibility in AWS** — status of current and historical runs, step-level progress
- **Minimize cost** — target under $5/month at modest scale
- **Secure storage** of Glooko passwords and Nightscout API secrets
- **Prevent runaway AWS charges** via layered caps and an auto-tripping circuit breaker with operator override

### 3.2 Non-Goals

- Uploading CGM glucose values (unchanged)
- Real-time sync (Glooko Omnipod 5 data remains 1–4 hours delayed)
- Clinical dosing or FDA-regulated medical device behavior
- Web admin portal in v1 (AWS Console + API + app status fields suffice)
- Multiple bridges per Cognito user in v1 (`bridgeId` = Cognito `sub`)

---

## 4. Proposed Architecture

### 4.1 Pattern

**Thin-client, serverless orchestration.** The Android app authenticates via Cognito, submits configuration and run requests to API Gateway, and polls for async results. A Step Functions state machine orchestrates test and sync workflows. A single EventBridge rate rule (1 minute) drives scheduled sync for all due bridges.

```
┌─────────────────┐         JWT          ┌──────────────────┐
│  Android App    │ ──────────────────►  │  API Gateway     │
│  (Compose UI)   │ ◄──────────────────  │  HTTP API        │
└─────────────────┘    poll status      └────────┬─────────┘
                                                  │
                    ┌─────────────────────────────┼─────────────────────────────┐
                    │                             ▼                             │
                    │  ┌──────────────┐   ┌──────────────┐   ┌─────────────┐  │
                    │  │ trigger_run  │   │  scheduler   │   │  circuit_   │  │
                    │  │   Lambda     │   │   Lambda     │   │  breaker    │  │
                    │  └──────┬───────┘   └──────┬───────┘   └──────┬──────┘  │
                    │         │                  │                   │          │
                    │         └──────────┬───────┘                   │          │
                    │                    ▼                           SSM          │
                    │         ┌─────────────────────┐              (global)       │
                    │         │  Step Functions     │                           │
                    │         │  (test | sync)      │                           │
                    │         └──────────┬──────────┘                           │
                    │                    ▼                                       │
                    │         ┌─────────────────────┐      ┌──────────┐           │
                    │         │   sync_worker       │ ◄──► │ DynamoDB │           │
                    │         │   Lambda            │      └──────────┘           │
                    │         └──────────┬──────────┘                           │
                    │                    │                                       │
                    └────────────────────┼───────────────────────────────────────┘
                                         │
                          ┌──────────────┴──────────────┐
                          ▼                             ▼
                   ┌─────────────┐               ┌─────────────┐
                   │ Glooko API  │               │ Gluroo GGC  │
                   │  (read)     │               │ Nightscout  │
                   └─────────────┘               │  (write)    │
                                                 └─────────────┘
```

### 4.2 Component inventory

| Component | Purpose | Cost profile |
|-----------|---------|--------------|
| API Gateway HTTP API | REST entry, JWT authorization | Per-request; negligible at this scale |
| Cognito User Pool | Multi-tenant identity | Free tier to 50k MAU |
| Step Functions Standard | Workflow orchestration + AWS Console visibility | Per state transition; cents/month |
| Lambda (Python 3.12) | API handlers, sync engine, scheduler, circuit breaker | Per-invocation; free tier covers typical use |
| DynamoDB on-demand | Bridge config, run history, dedupe keys | Per-request |
| SSM Parameter Store (Advanced) | Per-bridge secrets + global circuit breaker state | **$0.05/bridge/month** |
| EventBridge | Scheduler tick (1 min) + circuit breaker poll (5 min) | Negligible |
| CloudWatch + SNS | Logs, alarms, operator email notifications | Low |

### 4.3 Deliberately excluded

| Excluded | Rationale |
|----------|-----------|
| ECS / Fargate / EC2 | Always-on cost; operational overhead |
| VPC + NAT Gateway | Common source of surprise bills ($30+/month idle) |
| RDS / Aurora | No relational query workload |
| Secrets Manager (per user) | $0.40/secret/month vs $0.05 SSM — 8× at scale |
| Per-user EventBridge schedules | N rules instead of 1 fan-out tick; harder to cap globally |
| API Gateway WebSockets | Polling is sufficient; adds complexity |

---

## 5. Workflow Design

### 5.1 State machine

One Step Functions state machine handles both **test** (dry-run) and **sync** (upload) via a `mode` parameter.

```
LoadConfig → TestGlooko → TestNightscout → FetchAndPreview
                                                │
                        ┌───────────────────────┴───────────────────────┐
                        │ mode=test                              mode=sync
                        ▼                                               ▼
                   RecordRun                              ChoiceUpload
                                                              │
                                         ┌────────────────────┴────────────────────┐
                                         │ new treatments                     nothing
                                         ▼                                         ▼
                                  UploadTreatments                          RecordRun
                                         │
                                         ▼
                                   UpdateState
                                         │
                                         ▼
                                    RecordRun → [end]
```

**Execution naming:** `{bridgeId}-{runId}` — searchable in the Step Functions console.

**Retry policy:** Zero retries on all task states. Fail fast.

**Lambda timeout:** 120 seconds per `sync_worker` invocation.

### 5.2 Run lifecycle

| Status | Meaning |
|--------|---------|
| `QUEUED` | Run record created; Step Functions starting |
| `RUNNING` | Workflow in progress; `currentStep` updated at each state |
| `SUCCEEDED` | Completed; diagnostics and result summary available |
| `FAILED` | Error captured; `lastError` updated on bridge record |

The app polls `GET /runs/{runId}` every 2 seconds after Test or Sync Now (same UX as today's `isBusy` spinner).

### 5.3 Scheduled sync

- EventBridge `rate(1 minute)` → `scheduler` Lambda
- Query GSI: `syncEnabled=1 AND nextScheduledSyncEpochMs <= now`
- For each due bridge: skip if `RUNNING`/`QUEUED` exists; advance `nextScheduledSyncEpochMs` **before** starting execution (idempotency)
- Fan-out cap: **50 bridges per tick**
- WorkManager on Android is **retired**

---

## 6. Data Model

### 6.1 DynamoDB tables

**`bridges`** (PK: `bridgeId`)

| Attribute | Type | Notes |
|-----------|------|-------|
| `bridgeId` | String | Cognito `sub` |
| `glookoEmail` | String | Non-secret |
| `nightscoutUrl` | String | Non-secret |
| `useTokenAuth` | Boolean | |
| `syncEnabled` | Boolean | Per-bridge toggle |
| `syncIntervalMinutes` | Number | Server-enforced minimum 1 |
| `backfillDays` | Number | 1–30 |
| `syncFromOverride` | String | Optional `yyyy-MM-dd HH:mm` |
| `postPumpModeNotes` | Boolean | |
| `jitterInsulinTimestamps` | Boolean | Testing only |
| `lastSuccessfulSyncEpochMs` | Number | |
| `nextScheduledSyncEpochMs` | Number | Scheduler cursor |
| `lastPumpModeNote` | String | Dedupe pump notes |
| `lastError` | String | |
| `lastBolusesUploaded` | Number | |

GSI: `sync-enabled-index` — PK `syncEnabled` (0/1), SK `nextScheduledSyncEpochMs`

**`sync_runs`** (PK: `bridgeId`, SK: `runId`)

| Attribute | Notes |
|-----------|-------|
| `mode` | `test` or `sync` |
| `status` | `QUEUED` / `RUNNING` / `SUCCEEDED` / `FAILED` |
| `currentStep` | Step Functions state name |
| `executionArn` | Link to AWS Console |
| `startedAt`, `completedAt` | ISO timestamps |
| `diagnostics` | Human-readable log (same format as today's app) |
| `bolusesUploaded` | Result summary |
| `syncPreview` | JSON (optional) |
| `expiresAt` | TTL — 90 days |

**`synced_records`** (PK: `bridgeId`, SK: `dedupeKey`)

Deduplication keys: `eventType|createdAt|insulin|carbs|notes` (unchanged from current Kotlin logic).

### 6.2 SSM parameters

**Per bridge:** `/g2g/bridges/{bridgeId}/credentials` (SecureString, KMS-encrypted)

```json
{
  "glookoPassword": "...",
  "nightscoutSecret": "..."
}
```

**Global (circuit breaker):**

| Parameter | Purpose |
|-----------|---------|
| `/g2g/global/sync_enabled` | Master on/off (`true`/`false`) |
| `/g2g/global/circuit_breaker_tripped_at` | ISO timestamp of auto-trip |
| `/g2g/global/circuit_breaker_tripped_reason` | e.g. `lambda_invocations_12450` |
| `/g2g/global/circuit_breaker_override_until` | 24h operator override window |

---

## 7. API Contract

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `PUT` | `/settings` | JWT | Save config; secrets → SSM, settings → DynamoDB |
| `POST` | `/runs` | JWT | Body: `{mode: "test"\|"sync"}` → `{runId}` |
| `GET` | `/runs/{runId}` | JWT | Poll status, diagnostics, preview |
| `GET` | `/runs?limit=20` | JWT | Recent run history (limit capped at 50) |
| `GET` | `/status` | JWT | Current run + last sync + circuit breaker state |
| `POST` | `/admin/reset-sync` | JWT | Clear `lastSuccessfulSyncEpochMs` |
| `POST` | `/admin/clear-history` | JWT | Delete `synced_records` for bridge |
| `GET` | `/admin/circuit-breaker` | `g2g-admins` | Trip state, reason, override expiry |
| `POST` | `/admin/circuit-breaker/override` | `g2g-admins` | 24-hour override — sync resumes despite trip |
| `POST` | `/admin/circuit-breaker/reset` | `g2g-admins` | Full clear; normal operation resumes |

**Error codes:**

| Code | Meaning |
|------|---------|
| `429` | Per-bridge rate cap exceeded |
| `503` | `SyncPaused` — circuit breaker tripped, no active override |

`GET /status` response includes `syncPaused`, `circuitBreakerTripped`, `overrideUntil` for app UX.

---

## 8. Security Architecture

### 8.1 Secrets handling

| Requirement | Implementation |
|-------------|----------------|
| No plaintext secrets in DynamoDB | Secrets in SSM SecureString only |
| No secrets in logs | Redaction in `sync_worker`; structured logging with field masking |
| Least-privilege IAM | `ssm:GetParameter` scoped to `/g2g/bridges/{bridgeId}/*` via JWT claim condition |
| TLS in transit | API Gateway HTTPS only |
| Android credential handling | Send secrets on Save only; do not re-fetch from cloud |

### 8.2 Authentication and tenant isolation

- **Cognito User Pool** with SRP authentication from Android
- **API Gateway JWT authorizer** on all routes
- **`bridgeId` = Cognito `sub`** — one bridge per user in v1
- **Defense in depth:** Lambda validates request `bridgeId` matches JWT `sub`
- **Admin group `g2g-admins`:** circuit breaker override/reset; assigned manually in Cognito console

### 8.3 Threat model summary

| Threat | Likelihood | Mitigation |
|--------|------------|------------|
| Cross-tenant data access | Low (if JWT enforced) | JWT authorizer + Lambda claim check |
| Credential exfiltration via logs | Medium | Log redaction; no secret echo in diagnostics |
| API abuse / cost attack | Low–Medium | Throttling, per-bridge caps, circuit breaker |
| Glooko API ToS / breakage | Medium | Unofficial API; monitor FAILED runs; parity tests |
| SSM parameter misconfiguration | Low | IaC-only changes; IAM conditions |

---

## 9. Cost Model

### 9.1 Steady-state estimates

| Scale | Sync interval | Estimated monthly cost |
|-------|---------------|------------------------|
| 10 bridges | 15 min | **$2–3** |
| 50 bridges | 15 min | **$5–8** |
| 100 bridges | 15 min | **$10–15** |

Primary cost drivers: SSM parameters ($0.05/bridge/month), Lambda invocations, DynamoDB writes for dedupe records. Step Functions, API Gateway, and EventBridge are rounding errors at this scale.

### 9.2 Worst-case ceiling

Even if the scheduler has a bug, infrastructure caps bound spend:

| Cap | Effect |
|-----|--------|
| `sync_worker` concurrency = 5 | Max 5 parallel syncs globally |
| Lambda timeout = 120s | No hung processes |
| Circuit breaker auto-trip | Pauses all sync at 2,000 Lambda invocations/hour |
| Daily per-bridge cap = 200 runs | Limits per-tenant abuse |

**Estimated worst-case before circuit breaker:** ~$1–2/day on Lambda. Not hundreds or thousands.

---

## 10. Cost Guardrails and Circuit Breaker

Runaway spend is prevented by **layered defenses**, not operator vigilance.

### 10.1 Application-level caps

| Control | Limit | Enforcement |
|---------|-------|-------------|
| Active runs per bridge | 1 | DynamoDB conditional write on `POST /runs` |
| Scheduler fan-out per tick | 50 bridges | `scheduler` Lambda pagination cap |
| Runs per bridge per day | 200 | `trigger_run` counts today's `sync_runs` |
| Test runs per bridge per hour | 20 | `trigger_run` when `mode=test` |
| Minimum sync interval | 5 minutes | `PUT /settings` validation |
| Scheduler idempotency | Advance `nextScheduledSync` before SF start | Conditional DynamoDB update |
| Step Functions retries | 0 | State machine definition |
| Poll history limit | 50 | `GET /runs?limit=` cap |

### 10.2 Infrastructure caps

| Resource | Cap |
|----------|-----|
| `sync_worker` Lambda | `reservedConcurrentExecutions: 5` |
| `scheduler` Lambda | `reservedConcurrentExecutions: 1` |
| `trigger_run` Lambda | `reservedConcurrentExecutions: 10` |
| API Gateway stage | 50 req/s, burst 100 |

### 10.3 Circuit breaker Lambda

**Triggers:**

1. CloudWatch alarms → SNS → `circuit_breaker` Lambda (immediate)
2. EventBridge `rate(5 minutes)` → metric poll (backup)

**Trip conditions (any one fires):**

| Metric | Threshold |
|--------|-----------|
| Lambda Invocations (1h sum) | > 2,000 |
| Step Functions ExecutionsStarted (1h sum) | > 1,000 |
| Global `sync_runs` count (daily) | > 3,000 |

**On trip:**

1. Set `sync_enabled = false`
2. Record `tripped_at` and `tripped_reason` in SSM
3. Send SNS email to operator
4. All new runs return `503 SyncPaused`

**Operator controls:**

| Action | Endpoint / CLI | Behavior |
|--------|----------------|----------|
| **24h override** | `POST /admin/circuit-breaker/override` | Sets `override_until = now + 24h`; sync allowed during window even if tripped |
| **Full reset** | `POST /admin/circuit-breaker/reset` | Clears trip, re-enables sync, clears override |
| **CLI fallback** | `aws ssm put-parameter ... override_until` | Emergency override without API |

Override expires automatically; if still tripped after 24h, sync pauses again until reset.

### 10.4 Billing backstop

| Alarm | Threshold | Action |
|-------|-----------|--------|
| AWS Budget | $10/month forecasted | SNS email at 80% and 100% |
| CloudWatch | Lambda Invocations > 10,000/day | SNS + circuit breaker |
| CloudWatch | Step Functions starts > 5,000/day | SNS + circuit breaker |

---

## 11. Observability and Operations

| Need | Solution |
|------|----------|
| Workflow visibility | Step Functions console — execution graph, per-step I/O |
| Run history | DynamoDB `sync_runs` (90-day TTL) + `GET /runs` API |
| Current run status | `GET /status` + `GET /runs/{runId}` |
| Failure debugging | CloudWatch Logs (Lambda + Step Functions); secrets redacted |
| Cost anomaly | Circuit breaker + Budget alarm + SNS email |
| Emergency stop | SSM `sync_enabled=false` or circuit breaker override/reset |

**Operator runbook (summary):**

1. **Sync paused unexpectedly** → Check `GET /admin/circuit-breaker` or SSM global params
2. **Need to resume for 24h** → `POST /admin/circuit-breaker/override`
3. **Resume permanently** → `POST /admin/circuit-breaker/reset`
4. **Investigate failure** → Step Functions console → execution by `{bridgeId}-{runId}`

---

## 12. Android Client Changes

The existing Jetpack Compose UI in `MainScreen.kt` is **unchanged in layout and fields**. Changes are behind the ViewModel/repository layer:

| Today | After migration |
|-------|-----------------|
| `SyncRepository` runs sync on-device | `CloudSyncRepository` calls AWS API |
| `EncryptedSharedPreferences` stores all credentials | Cognito auth; secrets sent to cloud on Save |
| WorkManager schedules background sync | AWS EventBridge scheduler |
| Room DB for dedupe + state | Cloud DynamoDB (Room optional as offline cache) |
| Immediate sync result | Async: `POST /runs` + poll `GET /runs/{runId}` |

**New components:** `AuthScreen` (Cognito sign-in), `BridgeApiClient` (OkHttp + JWT), build config for `API_BASE_URL` and Cognito pool IDs.

**Rollback:** Feature flag to retain on-device `SyncRepository` path until cloud validation is complete.

---

## 13. Backend Implementation

Sync logic is ported from Kotlin to **Python 3.12** Lambda, referencing existing unit test fixtures for parity.

| Python module | Kotlin source |
|---------------|---------------|
| `glooko_client.py` | `GlookoClient.kt` |
| `glooko_parser.py` | `GlookoParser.kt` |
| `nightscout_client.py` | `NightscoutClient.kt` |
| `treatment_mapper.py` | `TreatmentMapper.kt` |
| `sync_window.py` | `SyncWindow.kt` |
| `sync_engine.py` | `SyncRepository.kt` |

Infrastructure as Code: **AWS SAM** (`infra/template.yaml`). One-command deploy via `deploy.ps1`.

---

## 14. Implementation Plan

| Phase | Deliverables | Estimate |
|-------|--------------|----------|
| **1 — Backend core** | Python sync port, pytest parity tests, SAM base stack (DynamoDB, SSM, Cognito) | 1–2 weeks |
| **2 — API + scheduler** | API routes, Step Functions, scheduler Lambda, circuit breaker, admin endpoints | 1–2 weeks |
| **3 — Android integration** | Cognito auth, `BridgeApiClient`, ViewModel wiring, remove WorkManager | 1 week |
| **4 — Hardening** | Guardrails in IaC, IAM review, budget alarms, operator runbook, README | 3–5 days |

**Go-live criteria:**

- All pytest and existing Kotlin test fixtures pass on Python port
- End-to-end test: Save → Test → Sync Now → verify Gluroo treatment
- Circuit breaker trip + override + reset verified manually
- Cost guardrails deployed and documented

---

## 15. Risks and Mitigations

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| Glooko API changes | Sync breaks | Medium | Parity tests; FAILED run alarms |
| Python port behavioral drift | Wrong data uploaded | Medium | Reuse JSON fixtures from Kotlin tests |
| Credential breach | High | Low | SSM SecureString, IAM scoping, log redaction |
| Scheduler bug → cost spike | Medium | Low | Concurrency caps + circuit breaker + daily caps |
| Unofficial Glooko API ToS | Account risk | Medium | Document limitation; user accepts risk |
| Cognito lockout | Users cannot access | Low | Admin recovery documented |

---

## 16. Alternatives Considered

| Alternative | Verdict |
|-------------|---------|
| Keep sync on-device only | Rejected — no cloud observability; phone-dependent |
| ECS Fargate container | Rejected — always-on cost (~$15+/month minimum) |
| Secrets Manager per user | Rejected — 8× SSM cost at scale |
| Step Functions Express | Rejected — limited execution history for ops |
| Per-user EventBridge rules | Rejected — N schedules; harder global cap |
| SQS + Lambda (no Step Functions) | Rejected — no native workflow console visibility |
| Kotlin on JVM Lambda | Rejected — cold start latency, larger deployment package |

---

## 17. Open Decisions

| Decision | Recommendation | Status |
|----------|----------------|--------|
| One bridge per Cognito user | Yes for v1 | **Decided** |
| AWS-only scheduled sync | Yes; retire WorkManager | **Decided** |
| SSM vs Secrets Manager | SSM Advanced ($0.05/user) | **Decided** |
| Python vs Kotlin Lambda | Python (ecosystem fit for glooko-reader port) | **Proposed** |
| Room DB retention | Remove after cutover; optional cache | **Open** |
| Failed-run SNS to end users | Operator only in v1 | **Open** |

---

## 18. Approval Requested

Approval to proceed with **Phase 1** (Python sync port + SAM scaffold) is requested.

No production traffic migrates until Phase 3 Android integration is complete and parity tests pass. Rollback remains available via on-device sync feature flag until cloud path is validated in production.

---

*End of document*
