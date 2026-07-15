"""API Gateway Lambda — settings, runs, status, admin endpoints."""

from __future__ import annotations

import json
import os
from datetime import datetime, timedelta, timezone
from typing import Any

import boto3

from g2g.allow_sync import circuit_breaker_status, is_sync_allowed
from g2g import aws_store as store
from g2g.redact import redact
from g2g.sync_window import is_valid_custom_start_input
from handlers.circuit_breaker import reset, trip
from handlers.http_util import bridge_id_from_event, is_admin, parse_body, response, route_key

MAX_RUNS_PER_DAY = 200
MAX_TEST_RUNS_PER_HOUR = 20
MIN_SYNC_INTERVAL = 1


def handler(event: dict[str, Any], _context: Any) -> dict[str, Any]:
    try:
        method, path = route_key(event)
        bridge_id = bridge_id_from_event(event)

        if method == "PUT" and path == "/settings":
            return put_settings(bridge_id, parse_body(event))
        if method == "POST" and path == "/runs":
            return post_run(bridge_id, parse_body(event))
        if method == "GET" and path.startswith("/runs/"):
            rest = path[len("/runs/") :]
            if "/" in rest:
                run_id, suffix = rest.split("/", 1)
                if suffix == "records":
                    return get_run_records(bridge_id, run_id)
                return response(404, {"error": f"Not found: {method} {path}"})
            return get_run(bridge_id, rest)
        if method == "GET" and path == "/runs":
            params = event.get("queryStringParameters") or {}
            limit = int(params.get("limit") or 20)
            limit = max(1, min(200, limit))
            return list_runs(bridge_id, limit)
        if method == "GET" and path == "/status":
            return get_status(bridge_id, event)
        if method == "POST" and path == "/admin/reset-sync":
            return reset_sync(bridge_id)
        if method == "POST" and path == "/admin/clear-history":
            return clear_history(bridge_id)
        if method == "GET" and path == "/admin/circuit-breaker":
            if not is_admin(event):
                return response(403, {"error": "Admin required"})
            return get_circuit_breaker()
        if method == "POST" and path == "/admin/circuit-breaker/trip":
            if not is_admin(event):
                return response(403, {"error": "Admin required"})
            return trip_circuit_breaker()
        if method == "POST" and path == "/admin/circuit-breaker/override":
            if not is_admin(event):
                return response(403, {"error": "Admin required"})
            return override_circuit_breaker()
        if method == "POST" and path == "/admin/circuit-breaker/reset":
            if not is_admin(event):
                return response(403, {"error": "Admin required"})
            return reset_circuit_breaker()
        return response(404, {"error": f"Not found: {method} {path}"})
    except PermissionError as exc:
        return response(401, {"error": str(exc)})
    except Exception as exc:
        return response(500, {"error": redact(str(exc))})


def put_settings(bridge_id: str, body: dict[str, Any]) -> dict[str, Any]:
    override = body.get("syncFromOverride") or ""
    if not is_valid_custom_start_input(override):
        return response(
            400,
            {"error": "Sync from must be blank or yyyy-MM-dd HH:mm (local time)"},
        )
    interval = int(body.get("syncIntervalMinutes", 15))
    if interval < MIN_SYNC_INTERVAL:
        body["syncIntervalMinutes"] = MIN_SYNC_INTERVAL

    password = body.get("glookoPassword")
    secret = body.get("nightscoutSecret")
    if password is not None and secret is not None and password != "" and secret != "":
        store.save_credentials(bridge_id, password, secret)
    elif password or secret:
        # Partial update: merge with existing
        try:
            existing = store.load_credentials(bridge_id)
        except Exception:
            existing = {"glookoPassword": "", "nightscoutSecret": ""}
        store.save_credentials(
            bridge_id,
            password if password else existing.get("glookoPassword", ""),
            secret if secret else existing.get("nightscoutSecret", ""),
        )

    item = store.upsert_bridge_settings(bridge_id, body)
    public = {k: v for k, v in item.items() if k not in ("glookoPassword", "nightscoutSecret")}
    return response(200, {"settings": public})


def post_run(bridge_id: str, body: dict[str, Any]) -> dict[str, Any]:
    mode = (body.get("mode") or "sync").lower()
    if mode not in ("test", "sync"):
        return response(400, {"error": "mode must be test or sync"})

    globals_ = store.get_global_params()
    if not is_sync_allowed(globals_):
        status = circuit_breaker_status(globals_)
        return response(503, {"error": "SyncPaused", **status})

    if store.has_active_run(bridge_id):
        return response(429, {"error": "A run is already QUEUED or RUNNING for this bridge"})

    if store.count_runs_today(bridge_id) >= MAX_RUNS_PER_DAY:
        return response(429, {"error": f"Daily run cap ({MAX_RUNS_PER_DAY}) exceeded"})

    if mode == "test" and store.count_test_runs_last_hour(bridge_id) >= MAX_TEST_RUNS_PER_HOUR:
        return response(429, {"error": f"Test hourly cap ({MAX_TEST_RUNS_PER_HOUR}) exceeded"})

    bridge = store.get_bridge(bridge_id)
    if not bridge:
        return response(400, {"error": "Settings not saved yet"})

    # Manual sync: advance schedule here so scheduler does not immediately re-fire.
    # Align to UTC minute boundaries (same as scheduler) so EventBridge does not skip.
    if mode == "sync" and bridge.get("syncEnabled"):
        interval = max(MIN_SYNC_INTERVAL, int(bridge.get("syncIntervalMinutes") or 15))
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        minute_ms = 60_000
        aligned_now = (now_ms // minute_ms) * minute_ms
        store.set_next_scheduled(bridge_id, aligned_now + interval * minute_ms)

    run = store.create_run(bridge_id, mode)
    sfn = boto3.client("stepfunctions")
    execution = sfn.start_execution(
        stateMachineArn=os.environ["STATE_MACHINE_ARN"],
        name=f"{bridge_id[:8]}-{run['runId']}"[:80],
        input=json.dumps(
            {
                "bridgeId": bridge_id,
                "runId": run["runId"],
                "mode": mode,
            }
        ),
    )
    store.update_run(bridge_id, run["runId"], executionArn=execution["executionArn"], status="QUEUED")
    return response(
        202,
        {
            "runId": run["runId"],
            "mode": mode,
            "status": "QUEUED",
            "executionArn": execution["executionArn"],
        },
    )


def get_run(bridge_id: str, run_id: str) -> dict[str, Any]:
    item = store.get_run(bridge_id, run_id)
    if not item:
        return response(404, {"error": "Run not found"})
    if "diagnostics" in item:
        item["diagnostics"] = redact(item.get("diagnostics"))
    return response(200, item)


def get_run_records(bridge_id: str, run_id: str) -> dict[str, Any]:
    run = store.get_run(bridge_id, run_id)
    if not run:
        return response(404, {"error": "Run not found"})
    records = store.list_run_records(bridge_id, run_id)
    return response(200, {"runId": run_id, "records": records})


def list_runs(bridge_id: str, limit: int) -> dict[str, Any]:
    items = store.list_runs(bridge_id, limit)
    for item in items:
        if "diagnostics" in item:
            item["diagnostics"] = redact(item.get("diagnostics"))
    return response(200, {"runs": items})


def get_status(bridge_id: str, event: dict[str, Any] | None = None) -> dict[str, Any]:
    bridge = store.get_bridge(bridge_id) or {}
    runs = store.list_runs(bridge_id, limit=5)
    current = next((r for r in runs if r.get("status") in ("QUEUED", "RUNNING")), None)
    last = next((r for r in runs if r.get("status") in ("SUCCEEDED", "FAILED")), None)
    cb = circuit_breaker_status(store.get_global_params())
    public_bridge = {
        "bridgeId": bridge.get("bridgeId") or bridge_id,
        "lastSuccessfulSyncEpochMs": bridge.get("lastSuccessfulSyncEpochMs", 0),
        "nextScheduledSyncEpochMs": bridge.get("nextScheduledSyncEpochMs", 0),
        "lastError": bridge.get("lastError"),
        "lastBolusesUploaded": bridge.get("lastBolusesUploaded", 0),
        "lastPumpModeNote": bridge.get("lastPumpModeNote"),
        "syncEnabled": bridge.get("syncEnabled", False),
        "glookoEmail": bridge.get("glookoEmail", ""),
        "nightscoutUrl": bridge.get("nightscoutUrl", ""),
        "useTokenAuth": bridge.get("useTokenAuth", False),
        "backfillDays": bridge.get("backfillDays", 7),
        "syncFromOverride": bridge.get("syncFromOverride", ""),
        "postPumpModeNotes": bridge.get("postPumpModeNotes", True),
        "jitterInsulinTimestamps": bridge.get("jitterInsulinTimestamps", False),
        "syncIntervalMinutes": bridge.get("syncIntervalMinutes", 15),
        "timezone": bridge.get("timezone") or "America/Los_Angeles",
        "updatedAt": bridge.get("updatedAt"),
    }
    return response(
        200,
        {
            "bridge": public_bridge,
            "currentRun": current,
            "lastRun": last,
            "isAdmin": is_admin(event) if event else False,
            **cb,
        },
    )


def reset_sync(bridge_id: str) -> dict[str, Any]:
    state = store.DynamoStateStore(bridge_id).get()
    state.last_successful_sync_epoch_ms = 0
    state.last_boluses_uploaded = 0
    state.last_error = None
    store.DynamoStateStore(bridge_id).upsert(state)
    return response(200, {"ok": True})


def clear_history(bridge_id: str) -> dict[str, Any]:
    store.DynamoDedupeStore(bridge_id).delete_all()
    bridge = store.get_bridge(bridge_id) or {"bridgeId": bridge_id}
    bridge["lastPumpModeNote"] = None
    store._tables()["bridges"].put_item(Item=bridge)
    return response(200, {"ok": True})


def get_circuit_breaker() -> dict[str, Any]:
    return response(200, circuit_breaker_status(store.get_global_params()))


def trip_circuit_breaker() -> dict[str, Any]:
    result = trip("manual_pull")
    # Merge written values — SSM GetParameters can briefly return pre-update values.
    globals_ = store.get_global_params()
    globals_["sync_enabled"] = "false"
    globals_["circuit_breaker_tripped_at"] = result["at"]
    globals_["circuit_breaker_tripped_reason"] = result["reason"]
    return response(200, circuit_breaker_status(globals_))


def override_circuit_breaker() -> dict[str, Any]:
    until = datetime.now(timezone.utc) + timedelta(hours=24)
    until_s = until.isoformat().replace("+00:00", "Z")
    store.put_global_param(
        "circuit_breaker_override_until",
        until_s,
    )
    globals_ = store.get_global_params()
    globals_["circuit_breaker_override_until"] = until_s
    return response(200, circuit_breaker_status(globals_))


def reset_circuit_breaker() -> dict[str, Any]:
    result = reset()
    globals_ = store.get_global_params()
    globals_["sync_enabled"] = "true"
    globals_["circuit_breaker_tripped_at"] = ""
    globals_["circuit_breaker_tripped_reason"] = ""
    globals_["circuit_breaker_override_until"] = ""
    status = circuit_breaker_status(globals_)
    status["resetAt"] = result.get("at")
    return response(200, status)
