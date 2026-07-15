"""EventBridge scheduler — start due sync runs (max 50/tick)."""

from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from typing import Any

import boto3

from g2g.allow_sync import is_sync_allowed
from g2g import aws_store as store

FANOUT_CAP = 50


def handler(event: dict[str, Any], _context: Any) -> dict[str, Any]:
    globals_ = store.get_global_params()
    if not is_sync_allowed(globals_):
        return {"skipped": True, "reason": "SyncPaused", "started": 0}

    now = datetime.now(timezone.utc)
    now_ms = int(now.timestamp() * 1000)
    due = store.list_due_bridges(now_ms, limit=FANOUT_CAP)
    sfn = boto3.client("stepfunctions")
    started = 0
    skipped = 0

    for bridge in due:
        bridge_id = bridge["bridgeId"]
        if store.has_active_run(bridge_id):
            skipped += 1
            continue
        interval = max(5, int(bridge.get("syncIntervalMinutes") or 15))
        expected_next = int(bridge.get("nextScheduledSyncEpochMs") or 0)
        new_next = now_ms + interval * 60_000
        if not store.claim_bridge_schedule(bridge_id, expected_next, new_next):
            skipped += 1
            continue
        run = store.create_run(bridge_id, "sync")
        try:
            execution = sfn.start_execution(
                stateMachineArn=os.environ["STATE_MACHINE_ARN"],
                name=f"{bridge_id[:8]}-{run['runId']}"[:80],
                input=json.dumps(
                    {
                        "bridgeId": bridge_id,
                        "runId": run["runId"],
                        "mode": "sync",
                    }
                ),
            )
            store.update_run(
                bridge_id,
                run["runId"],
                executionArn=execution["executionArn"],
                status="QUEUED",
            )
            started += 1
        except Exception as exc:
            store.update_run(
                bridge_id,
                run["runId"],
                status="FAILED",
                error=str(exc)[:500],
                completedAt=datetime.now(timezone.utc).isoformat(),
            )

    return {"started": started, "skipped": skipped, "due": len(due)}
