# Glooko Gluroo Bridge

Android app + optional AWS backend that reads insulin and pump data from [Glooko](https://www.glooko.com/) using the unofficial API approach from [spamsch/glooko-reader](https://github.com/spamsch/glooko-reader), then publishes bolus and pump events to [Gluroo](https://gluroo.com/) via its Nightscout-compatible backend (Gluroo Global Connect).

**CGM values are not uploaded.** Configure CGM separately in Gluroo (Dexcom, xDrip, etc.).

## Modes

| Mode | How it works |
|------|----------------|
| **Cloud (default when configured)** | Save / Test / Sync Now call AWS. Scheduled sync runs on EventBridge even if the phone is off. |
| **Local fallback** | Set `g2g.useCloudSync=false` or leave API URL blank. Sync runs on-device via WorkManager. |

## What it syncs

| Data | Destination |
|------|-------------|
| Bolus deliveries | Nightscout `Correction Bolus` treatments |
| Carbs at bolus time | `carbs` field on treatment |
| IOB at bolus time | `notes` field (informational) |
| Pump mode changes | Optional `Note` treatment |

## Important limitations

- **Not real-time**: Omnipod 5 syncs to Glooko every 1–4 hours.
- **Unofficial Glooko API**: Uses browser-login flow; may break if Glooko changes their web app.
- **Not for dosing**: For family sharing and retrospective monitoring only.
- Log into the **Glooko mobile app** at least once before using this bridge.

## Gluroo setup

1. In Gluroo: **Menu > Settings > Gluroo Global Connect Nightscout**
2. Copy your Nightscout URL and API secret into this app
3. Enter your Glooko email and password
4. Tap **Test** to verify both connections
5. Enable **AWS scheduled sync** (or local background sync) and tap **Save**
6. Confirm bolus events appear in the Gluroo event log after the next sync

## AWS deploy

Requires [AWS SAM CLI](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-sam-cli.html), Python 3.12+, and AWS credentials.

```powershell
cd infra
.\deploy.ps1 -EnvironmentName prod -AlertEmail you@example.com
```

Stack outputs include `ApiUrl`, `UserPoolId`, `UserPoolClientId`, and `StateMachineArn`.

Add to `gradle.properties` (project or `~/.gradle/gradle.properties`):

```properties
g2g.apiBaseUrl=https://xxxx.execute-api.REGION.amazonaws.com/prod
g2g.cognitoRegion=us-east-1
g2g.cognitoClientId=xxxxxxxx
g2g.useCloudSync=true
```

Add yourself to the Cognito group `g2g-admins` for circuit-breaker override/reset.

### Architecture (cloud)

- **API Gateway HTTP API** + **Cognito JWT** — app entry
- **Step Functions** — test/sync workflow (visible in AWS Console)
- **Lambda** — `api`, `sync_worker` (concurrency 5), `scheduler` (1/min), `circuit_breaker` (5 min + alarms)
- **DynamoDB** — bridges, sync_runs (90-day TTL), synced_records
- **SSM Parameter Store** — SecureString credentials per bridge + global kill switch / override

### Cost guardrails

- One active run per bridge; 200 runs/day/bridge; 20 tests/hour/bridge
- Scheduler fan-out max 50/tick; min interval 1 minute
- Circuit breaker trips on Lambda >2k/h, SFN >1k/h, or >3k global runs/day
- **24h override:** `POST /admin/circuit-breaker/override` (admin)
- **Full reset:** `POST /admin/circuit-breaker/reset` (admin)
- AWS Budget email at $10/month (when `AlertEmail` is set)

### Emergency override (CLI)

```bash
aws ssm put-parameter \
  --name /g2g/prod/global/circuit_breaker_override_until \
  --value "$(date -u -d '+24 hours' +%Y-%m-%dT%H:%M:%SZ)" \
  --type String --overwrite

# Full reset
aws ssm put-parameter --name /g2g/prod/global/sync_enabled --value true --type String --overwrite
aws ssm put-parameter --name /g2g/prod/global/circuit_breaker_tripped_at --value " " --type String --overwrite
aws ssm put-parameter --name /g2g/prod/global/circuit_breaker_tripped_reason --value " " --type String --overwrite
aws ssm put-parameter --name /g2g/prod/global/circuit_breaker_override_until --value " " --type String --overwrite
```

### Monitor runs

- **Step Functions** console → state machine `g2g-prod-sync` → Executions
- **API** `GET /status`, `GET /runs`, `GET /runs/{runId}`
- **CloudWatch Logs** `/aws/lambda/g2g-prod-sync-worker` (secrets redacted)

## Backend unit tests

```bash
cd backend
python -m pip install -r requirements.txt
python -m pytest
```

## Android build

Requires Android SDK and JDK 17.

```bash
./gradlew assembleDebug
```

Install the debug APK from `app/build/outputs/apk/debug/`.

## Architecture (code)

- `glooko/` — Glooko authentication and data parsers
- `nightscout/` — Gluroo GGC treatment publisher (local mode)
- `sync/` — WorkManager local sync with Room deduplication (fallback)
- `cloud/` — Cognito auth + Bridge API client + cloud sync repository
- `ui/` — Jetpack Compose settings and status screen
- `backend/` — Python sync engine + Lambda handlers
- `infra/` — AWS SAM template and deploy script

## License

MIT
