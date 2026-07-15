# AWS ops runbook

## View current circuit breaker state

```bash
aws ssm get-parameters --names \
  /g2g/prod/global/sync_enabled \
  /g2g/prod/global/circuit_breaker_tripped_at \
  /g2g/prod/global/circuit_breaker_tripped_reason \
  /g2g/prod/global/circuit_breaker_override_until \
  --query "Parameters[*].[Name,Value]" --output table
```

Or call `GET /admin/circuit-breaker` with an admin Cognito JWT.

## Pause all sync immediately

```bash
aws ssm put-parameter --name /g2g/prod/global/sync_enabled --value false --type String --overwrite
```

## Resume for 24 hours (override) despite a trip

```bash
# Windows PowerShell
$until = (Get-Date).ToUniversalTime().AddHours(24).ToString("yyyy-MM-ddTHH:mm:ssZ")
aws ssm put-parameter --name /g2g/prod/global/circuit_breaker_override_until --value $until --type String --overwrite
```

## Full reset

```bash
aws ssm put-parameter --name /g2g/prod/global/sync_enabled --value true --type String --overwrite
aws ssm put-parameter --name /g2g/prod/global/circuit_breaker_tripped_at --value " " --type String --overwrite
aws ssm put-parameter --name /g2g/prod/global/circuit_breaker_tripped_reason --value " " --type String --overwrite
aws ssm put-parameter --name /g2g/prod/global/circuit_breaker_override_until --value " " --type String --overwrite
```

## Add admin user to Cognito group

```bash
aws cognito-idp admin-add-user-to-group \
  --user-pool-id <UserPoolId> \
  --username you@example.com \
  --group-name g2g-admins
```
