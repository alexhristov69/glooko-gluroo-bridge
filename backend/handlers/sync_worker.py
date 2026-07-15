"""Step Functions sync_worker — runs test or sync for a bridge."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from g2g import aws_store as store
from g2g.redact import redact
from g2g.sync_engine import SyncEngine


def _preview_to_dict(preview) -> dict[str, Any] | None:
    if preview is None:
        return None
    return {
        "syncWindowStart": preview.sync_window_start.isoformat(),
        "syncWindowEnd": preview.sync_window_end.isoformat(),
        "windowSource": preview.window_source,
        "bolusesFound": preview.boluses_found,
        "bolusesAlreadySynced": preview.boluses_already_synced,
        "treatmentsToUpload": len(preview.treatments_to_upload),
        "bolusesToUpload": preview.boluses_to_upload,
        "pumpNoteToUpload": preview.pump_note_to_upload,
        "jsonPayload": preview.json_payload,
        "devices": [{"name": d.name, "deviceType": d.device_type} for d in preview.devices],
    }


def handler(event: dict[str, Any], _context: Any) -> dict[str, Any]:
    bridge_id = event["bridgeId"]
    run_id = event["runId"]
    mode = event.get("mode", "sync")
    step = event.get("step", "RunSync")

    store.update_run(
        bridge_id,
        run_id,
        status="RUNNING",
        currentStep=step,
    )

    try:
        bridge = store.get_bridge(bridge_id)
        if not bridge:
            raise RuntimeError("Bridge settings not found")
        creds = store.load_credentials(bridge_id)
        settings = store.bridge_to_settings(bridge, creds)
        engine = SyncEngine(
            dedupe=store.DynamoDedupeStore(bridge_id),
            state=store.DynamoStateStore(bridge_id),
        )

        if mode == "test":
            result = engine.test_connections(settings)
            diagnostics = redact(result.diagnostics)
            store.update_run(
                bridge_id,
                run_id,
                status="SUCCEEDED" if result.glooko_ok else "FAILED",
                currentStep="RecordRun",
                completedAt=datetime.now(timezone.utc).isoformat(),
                diagnostics=diagnostics,
                glookoOk=result.glooko_ok,
                nightscoutOk=result.nightscout_ok,
                glookoError=result.glooko_error,
                nightscoutError=result.nightscout_error,
                syncPreview=_preview_to_dict(result.sync_preview),
                bolusesUploaded=0,
            )
            return {
                "bridgeId": bridge_id,
                "runId": run_id,
                "mode": mode,
                "success": result.glooko_ok,
                "diagnostics": diagnostics,
            }

        result = engine.run_sync(settings, run_id=run_id)
        diagnostics = redact(result.diagnostics or "")
        store.update_run(
            bridge_id,
            run_id,
            status="SUCCEEDED" if result.success else "FAILED",
            currentStep="RecordRun",
            completedAt=datetime.now(timezone.utc).isoformat(),
            diagnostics=diagnostics,
            bolusesUploaded=result.boluses_uploaded,
            pumpNoteUploaded=result.pump_note_uploaded,
            error=result.error,
            syncPreview=_preview_to_dict(result.sync_preview),
        )

        # Do not touch nextScheduledSyncEpochMs here. Scheduler claims it before
        # start; API advances it for manual Sync now. Rewriting on completion
        # pushed the cursor past the next EventBridge tick (interval+1).

        return {
            "bridgeId": bridge_id,
            "runId": run_id,
            "mode": mode,
            "success": result.success,
            "bolusesUploaded": result.boluses_uploaded,
            "diagnostics": diagnostics,
        }
    except Exception as exc:
        msg = redact(str(exc))
        store.update_run(
            bridge_id,
            run_id,
            status="FAILED",
            currentStep=step,
            completedAt=datetime.now(timezone.utc).isoformat(),
            error=msg,
            diagnostics=msg,
        )
        raise
