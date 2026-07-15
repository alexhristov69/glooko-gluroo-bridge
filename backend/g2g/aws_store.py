"""DynamoDB + SSM accessors for bridge config, runs, and secrets."""

from __future__ import annotations

import json
import os
import time
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any

import boto3
from boto3.dynamodb.conditions import Attr, Key

from g2g.models import AppSettings, SyncState


def _tables():
    ddb = boto3.resource("dynamodb")
    return {
        "bridges": ddb.Table(os.environ["BRIDGES_TABLE"]),
        "runs": ddb.Table(os.environ["SYNC_RUNS_TABLE"]),
        "records": ddb.Table(os.environ["SYNCED_RECORDS_TABLE"]),
    }


def _ssm():
    return boto3.client("ssm")


def credentials_path(bridge_id: str) -> str:
    prefix = os.environ.get("SSM_PREFIX", "/g2g")
    return f"{prefix}/bridges/{bridge_id}/credentials"


def global_param_name(suffix: str) -> str:
    prefix = os.environ.get("SSM_PREFIX", "/g2g")
    return f"{prefix}/global/{suffix}"


def get_global_params() -> dict[str, str]:
    ssm = _ssm()
    names = [
        global_param_name("sync_enabled"),
        global_param_name("circuit_breaker_tripped_at"),
        global_param_name("circuit_breaker_tripped_reason"),
        global_param_name("circuit_breaker_override_until"),
    ]
    out: dict[str, str] = {}
    try:
        resp = ssm.get_parameters(Names=names, WithDecryption=True)
        for p in resp.get("Parameters", []):
            key = p["Name"].rsplit("/", 1)[-1]
            value = (p.get("Value") or "").strip()
            out[key] = value
    except Exception:
        pass
    # Defaults
    out.setdefault("sync_enabled", "true")
    out.setdefault("circuit_breaker_tripped_at", "")
    out.setdefault("circuit_breaker_tripped_reason", "")
    out.setdefault("circuit_breaker_override_until", "")
    if not out.get("sync_enabled"):
        out["sync_enabled"] = "true"
    return out


def put_global_param(suffix: str, value: str) -> None:
    _ssm().put_parameter(
        Name=global_param_name(suffix),
        Value=value if value is not None else "",
        Type="String",
        Overwrite=True,
    )


def load_credentials(bridge_id: str) -> dict[str, str]:
    resp = _ssm().get_parameter(Name=credentials_path(bridge_id), WithDecryption=True)
    return json.loads(resp["Parameter"]["Value"])


def save_credentials(bridge_id: str, glooko_password: str, nightscout_secret: str) -> None:
    payload = json.dumps(
        {"glookoPassword": glooko_password, "nightscoutSecret": nightscout_secret}
    )
    _ssm().put_parameter(
        Name=credentials_path(bridge_id),
        Value=payload,
        Type="SecureString",
        Overwrite=True,
        Tier="Advanced",
    )


def get_bridge(bridge_id: str) -> dict[str, Any] | None:
    resp = _tables()["bridges"].get_item(Key={"bridgeId": bridge_id})
    return resp.get("Item")


def upsert_bridge_settings(bridge_id: str, body: dict[str, Any]) -> dict[str, Any]:
    tables = _tables()
    existing = get_bridge(bridge_id) or {"bridgeId": bridge_id}
    interval = int(body.get("syncIntervalMinutes", existing.get("syncIntervalMinutes", 15)))
    interval = max(5, min(240, interval))
    backfill = int(body.get("backfillDays", existing.get("backfillDays", 7)))
    backfill = max(1, min(30, backfill))
    sync_enabled = bool(body.get("syncEnabled", existing.get("syncEnabled", False)))

    item = {
        **existing,
        "bridgeId": bridge_id,
        "glookoEmail": (body.get("glookoEmail") or existing.get("glookoEmail") or "").strip(),
        "nightscoutUrl": (body.get("nightscoutUrl") or existing.get("nightscoutUrl") or "")
        .strip()
        .rstrip("/"),
        "useTokenAuth": bool(body.get("useTokenAuth", existing.get("useTokenAuth", False))),
        "syncEnabled": sync_enabled,
        "syncEnabledKey": "1" if sync_enabled else "0",
        "backfillDays": backfill,
        "syncFromOverride": (body.get("syncFromOverride") or "").strip(),
        "postPumpModeNotes": bool(
            body.get("postPumpModeNotes", existing.get("postPumpModeNotes", True))
        ),
        "jitterInsulinTimestamps": bool(
            body.get("jitterInsulinTimestamps", existing.get("jitterInsulinTimestamps", False))
        ),
        "syncIntervalMinutes": interval,
        "updatedAt": datetime.now(timezone.utc).isoformat(),
    }
    if sync_enabled and not existing.get("nextScheduledSyncEpochMs"):
        item["nextScheduledSyncEpochMs"] = int(datetime.now(timezone.utc).timestamp() * 1000)
    elif sync_enabled and body.get("syncEnabled") and not existing.get("syncEnabled"):
        item["nextScheduledSyncEpochMs"] = int(datetime.now(timezone.utc).timestamp() * 1000)
    tables["bridges"].put_item(Item=item)
    return item


def bridge_to_settings(item: dict[str, Any], creds: dict[str, str]) -> AppSettings:
    return AppSettings(
        glooko_email=item.get("glookoEmail", ""),
        glooko_password=creds.get("glookoPassword", ""),
        nightscout_url=item.get("nightscoutUrl", ""),
        nightscout_secret=creds.get("nightscoutSecret", ""),
        use_token_auth=bool(item.get("useTokenAuth", False)),
        sync_enabled=bool(item.get("syncEnabled", False)),
        backfill_days=int(item.get("backfillDays", 7)),
        sync_from_override=item.get("syncFromOverride", "") or "",
        post_pump_mode_notes=bool(item.get("postPumpModeNotes", True)),
        jitter_insulin_timestamps=bool(item.get("jitterInsulinTimestamps", False)),
        sync_interval_minutes=int(item.get("syncIntervalMinutes", 15)),
    )


def bridge_to_sync_state(item: dict[str, Any]) -> SyncState:
    return SyncState(
        last_successful_sync_epoch_ms=int(item.get("lastSuccessfulSyncEpochMs") or 0),
        next_scheduled_sync_epoch_ms=int(item.get("nextScheduledSyncEpochMs") or 0),
        last_pump_mode_note=item.get("lastPumpModeNote"),
        last_error=item.get("lastError"),
        last_boluses_uploaded=int(item.get("lastBolusesUploaded") or 0),
    )


def update_bridge_sync_state(bridge_id: str, state: SyncState) -> None:
    tables = _tables()
    expr = (
        "SET lastSuccessfulSyncEpochMs=:a, nextScheduledSyncEpochMs=:b, "
        "lastError=:d, lastBolusesUploaded=:e"
    )
    values: dict[str, Any] = {
        ":a": state.last_successful_sync_epoch_ms,
        ":b": state.next_scheduled_sync_epoch_ms,
        ":d": state.last_error or "",
        ":e": state.last_boluses_uploaded,
    }
    if state.last_pump_mode_note is None:
        expr += " REMOVE lastPumpModeNote"
    else:
        expr += ", lastPumpModeNote=:c"
        values[":c"] = state.last_pump_mode_note
    tables["bridges"].update_item(
        Key={"bridgeId": bridge_id},
        UpdateExpression=expr,
        ExpressionAttributeValues=values,
    )


class DynamoDedupeStore:
    def __init__(self, bridge_id: str):
        self.bridge_id = bridge_id
        self.table = _tables()["records"]

    def get_all_keys(self) -> set[str]:
        keys: set[str] = set()
        kwargs: dict[str, Any] = {
            "KeyConditionExpression": Key("bridgeId").eq(self.bridge_id),
            "ProjectionExpression": "dedupeKey",
        }
        while True:
            resp = self.table.query(**kwargs)
            for item in resp.get("Items", []):
                keys.add(item["dedupeKey"])
            if "LastEvaluatedKey" not in resp:
                break
            kwargs["ExclusiveStartKey"] = resp["LastEvaluatedKey"]
        return keys

    def insert_keys(self, records: list[tuple[str, str, str, int]]) -> None:
        with self.table.batch_writer() as batch:
            for dedupe_key, event_type, created_at, uploaded_at in records:
                batch.put_item(
                    Item={
                        "bridgeId": self.bridge_id,
                        "dedupeKey": dedupe_key,
                        "eventType": event_type,
                        "createdAt": created_at,
                        "uploadedAtEpochMs": uploaded_at,
                    }
                )

    def delete_all(self) -> None:
        keys = list(self.get_all_keys())
        with self.table.batch_writer() as batch:
            for k in keys:
                batch.delete_item(Key={"bridgeId": self.bridge_id, "dedupeKey": k})


class DynamoStateStore:
    def __init__(self, bridge_id: str):
        self.bridge_id = bridge_id

    def get(self) -> SyncState:
        item = get_bridge(self.bridge_id) or {}
        return bridge_to_sync_state(item)

    def upsert(self, state: SyncState) -> None:
        update_bridge_sync_state(self.bridge_id, state)


def create_run(bridge_id: str, mode: str, execution_arn: str | None = None) -> dict[str, Any]:
    run_id = str(uuid.uuid4())
    now = datetime.now(timezone.utc)
    item = {
        "bridgeId": bridge_id,
        "runId": run_id,
        "mode": mode,
        "status": "QUEUED",
        "currentStep": "Queued",
        "executionArn": execution_arn or "",
        "startedAt": now.isoformat(),
        "expiresAt": int((now + timedelta(days=90)).timestamp()),
        "dateKey": now.strftime("%Y-%m-%d"),
    }
    _tables()["runs"].put_item(Item=item)
    return item


def update_run(bridge_id: str, run_id: str, **fields: Any) -> None:
    if not fields:
        return
    expr_parts = []
    values: dict[str, Any] = {}
    names: dict[str, str] = {}
    for i, (k, v) in enumerate(fields.items()):
        nk = f"#k{i}"
        vk = f":v{i}"
        names[nk] = k
        values[vk] = v
        expr_parts.append(f"{nk}={vk}")
    _tables()["runs"].update_item(
        Key={"bridgeId": bridge_id, "runId": run_id},
        UpdateExpression="SET " + ", ".join(expr_parts),
        ExpressionAttributeNames=names,
        ExpressionAttributeValues=values,
    )


def get_run(bridge_id: str, run_id: str) -> dict[str, Any] | None:
    resp = _tables()["runs"].get_item(Key={"bridgeId": bridge_id, "runId": run_id})
    return resp.get("Item")


def list_runs(bridge_id: str, limit: int = 20) -> list[dict[str, Any]]:
    limit = max(1, min(50, limit))
    resp = _tables()["runs"].query(
        KeyConditionExpression=Key("bridgeId").eq(bridge_id),
        ScanIndexForward=False,
        Limit=limit,
    )
    return resp.get("Items", [])


def has_active_run(bridge_id: str) -> bool:
    resp = _tables()["runs"].query(
        KeyConditionExpression=Key("bridgeId").eq(bridge_id),
        ScanIndexForward=False,
        Limit=10,
    )
    for item in resp.get("Items", []):
        if item.get("status") in ("QUEUED", "RUNNING"):
            return True
    return False


def count_runs_today(bridge_id: str) -> int:
    date_key = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    resp = _tables()["runs"].query(
        KeyConditionExpression=Key("bridgeId").eq(bridge_id),
        FilterExpression=Attr("dateKey").eq(date_key),
        Select="COUNT",
    )
    return int(resp.get("Count") or 0)


def count_test_runs_last_hour(bridge_id: str) -> int:
    cutoff = datetime.now(timezone.utc) - timedelta(hours=1)
    resp = _tables()["runs"].query(
        KeyConditionExpression=Key("bridgeId").eq(bridge_id),
        FilterExpression=Attr("mode").eq("test") & Attr("startedAt").gte(cutoff.isoformat()),
        Select="COUNT",
    )
    return int(resp.get("Count") or 0)


def count_global_runs_today() -> int:
    date_key = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    # Use dateKey GSI if present; fallback scan with filter is OK for low volume ops
    table = _tables()["runs"]
    try:
        resp = table.query(
            IndexName="dateKey-index",
            KeyConditionExpression=Key("dateKey").eq(date_key),
            Select="COUNT",
        )
        return int(resp.get("Count") or 0)
    except Exception:
        resp = table.scan(FilterExpression=Attr("dateKey").eq(date_key), Select="COUNT")
        return int(resp.get("Count") or 0)


def list_due_bridges(now_ms: int, limit: int = 50) -> list[dict[str, Any]]:
    table = _tables()["bridges"]
    resp = table.query(
        IndexName="sync-enabled-index",
        KeyConditionExpression=Key("syncEnabledKey").eq("1")
        & Key("nextScheduledSyncEpochMs").lte(now_ms),
        Limit=limit,
    )
    return resp.get("Items", [])


def claim_bridge_schedule(bridge_id: str, expected_next: int, new_next: int) -> bool:
    """Advance nextScheduledSyncEpochMs before starting a run (idempotency)."""
    from botocore.exceptions import ClientError

    try:
        _tables()["bridges"].update_item(
            Key={"bridgeId": bridge_id},
            UpdateExpression="SET nextScheduledSyncEpochMs=:n",
            ConditionExpression=(
                "attribute_not_exists(nextScheduledSyncEpochMs) OR nextScheduledSyncEpochMs = :e"
            ),
            ExpressionAttributeValues={":n": new_next, ":e": expected_next},
        )
        return True
    except ClientError as exc:
        if exc.response["Error"]["Code"] == "ConditionalCheckFailedException":
            return False
        raise
