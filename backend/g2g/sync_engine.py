"""Sync orchestration — ported from SyncRepository.kt."""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Protocol
from zoneinfo import ZoneInfo

from g2g.glooko_client import GlookoClient
from g2g.glooko_parser import parse_bolus_entries, parse_devices, parse_pump_mode
from g2g.models import (
    AppSettings,
    DeviceInfo,
    NightscoutTreatment,
    PumpStatistics,
    SyncPreview,
    SyncState,
)
from g2g.nightscout_client import NightscoutClient, encode_treatments
from g2g import sync_window as sync_window_mod
from g2g import treatment_mapper as tm


class DedupeStore(Protocol):
    def get_all_keys(self) -> set[str]: ...

    def insert_keys(self, records: list[tuple[str, str, str, int]]) -> None:
        """records: (dedupe_key, event_type, created_at, uploaded_at_epoch_ms)"""
        ...


class StateStore(Protocol):
    def get(self) -> SyncState: ...

    def upsert(self, state: SyncState) -> None: ...


@dataclass
class ConnectionTestResult:
    glooko_ok: bool
    nightscout_ok: bool
    glooko_error: str | None = None
    nightscout_error: str | None = None
    diagnostics: str = ""
    sync_preview: SyncPreview | None = None


@dataclass
class SyncResult:
    success: bool
    boluses_uploaded: int = 0
    pump_note_uploaded: bool = False
    devices: list[DeviceInfo] | None = None
    pump_statistics: PumpStatistics | None = None
    sync_preview: SyncPreview | None = None
    error: str | None = None
    diagnostics: str | None = None


def _prettify(raw: str) -> str:
    try:
        return json.dumps(json.loads(raw), indent=2)
    except Exception:
        return raw


def format_preview_diagnostics(preview: SyncPreview, dry_run: bool = True) -> str:
    label = "Mock sync preview (not uploaded)" if dry_run else "Sync plan"
    lines = [
        f"=== {label} ===",
        f"Window: {preview.sync_window_start.isoformat()} → {preview.sync_window_end.isoformat()}",
        f"Source: {preview.window_source}",
        f"Boluses found: {preview.boluses_found}",
        f"Already synced: {preview.boluses_already_synced}",
        f"Treatments to upload: {len(preview.treatments_to_upload)}"
        + (" (dry-run)" if dry_run else ""),
    ]
    for t in preview.treatments_to_upload:
        insulin = t.insulin if t.insulin is not None else 0
        carbs = t.carbs if t.carbs is not None else 0
        lines.append(f"  {t.event_type} @ {t.created_at} | {insulin}u insulin | {carbs}g carbs")
    if preview.devices:
        lines.append(f"Devices: {', '.join(d.name for d in preview.devices)}")
    lines.append("JSON payload:")
    lines.append(_prettify(preview.json_payload))
    return "\n".join(lines)


class SyncEngine:
    def __init__(
        self,
        dedupe: DedupeStore,
        state: StateStore,
        zone: ZoneInfo | None = None,
    ):
        self.dedupe = dedupe
        self.state = state
        self.zone = zone or ZoneInfo("UTC")

    def build_sync_preview(
        self,
        settings: AppSettings,
        glooko_client: GlookoClient | None = None,
    ) -> SyncPreview:
        now = datetime.now(timezone.utc)
        existing = self.state.get()
        window = sync_window_mod.resolve(settings, existing, now, self.zone)
        client = glooko_client or GlookoClient(settings.glooko_email, settings.glooko_password)

        graph = client.get_graph_data(window.start, now)
        if graph is None:
            raise RuntimeError("Failed to fetch Glooko graph data (null response)")
        stats = client.get_statistics(window.start, now)
        device_data = client.get_device_settings()

        boluses = parse_bolus_entries(graph, self.zone)
        pump_stats = parse_pump_mode(stats) if stats else None
        devices = parse_devices(device_data, self.zone) if device_data else []

        known = self.dedupe.get_all_keys()
        new_bolus_treatments: list[NightscoutTreatment] = []
        synced_bolus_count = 0
        for bolus in boluses:
            uploads = tm.bolus_upload_treatments(bolus, settings.jitter_insulin_timestamps)
            bolus_treatment = uploads[0]
            if tm.deduplication_key(bolus_treatment) in known:
                synced_bolus_count += 1
            else:
                new_bolus_treatments.extend(uploads)

        pump_note = None
        if settings.post_pump_mode_notes and pump_stats is not None:
            note = tm.pump_mode_to_note(pump_stats)
            if note.notes != existing.last_pump_mode_note:
                pump_note = note

        treatments = list(new_bolus_treatments)
        if pump_note is not None:
            treatments.append(pump_note)

        payload = "[]" if not treatments else _prettify(encode_treatments(treatments))
        return SyncPreview(
            sync_window_start=window.start,
            sync_window_end=now,
            window_source=window.source,
            boluses_found=len(boluses),
            boluses_already_synced=synced_bolus_count,
            treatments_to_upload=treatments,
            devices=devices,
            pump_statistics=pump_stats,
            json_payload=payload,
            jitter_insulin_timestamps=settings.jitter_insulin_timestamps,
        )

    def test_connections(self, settings: AppSettings) -> ConnectionTestResult:
        log: list[str] = []
        glooko_ok = False
        nightscout_ok = False
        glooko_error = None
        nightscout_error = None
        glooko_client: GlookoClient | None = None

        log.append("=== Glooko ===")
        try:
            log.append(f"Authenticating as {settings.glooko_email}...")
            glooko_client = GlookoClient(settings.glooko_email, settings.glooko_password)
            summary = glooko_client.session_summary()
            if summary:
                log.append("Session:")
                log.append(summary)
            log.append("Fetching device settings...")
            test = glooko_client.test_connection()
            glooko_ok = True
            log.append(f"OK — {test.device_count} device(s) found")
            log.append(f"patient={test.patient_id}")
            log.append(f"api={test.api_base}")
            log.append(f"origin={test.dashboard_origin}")
        except Exception as exc:
            glooko_error = str(exc)
            log.append("FAILED")
            log.append(glooko_error)

        log.append("")
        log.append("=== Gluroo / Nightscout ===")
        try:
            log.append(f"URL: {settings.nightscout_url}")
            auth = "token query param" if settings.use_token_auth else "api-secret header (SHA1)"
            log.append(f"Auth: {auth}")
            ns = NightscoutClient(
                settings.nightscout_url,
                settings.nightscout_secret,
                settings.use_token_auth,
            )
            details = ns.test_connection()
            nightscout_ok = True
            log.append("OK")
            log.append(details)
        except Exception as exc:
            nightscout_error = str(exc)
            log.append("FAILED")
            log.append(nightscout_error)

        preview = None
        if glooko_ok and glooko_client is not None:
            log.append("")
            try:
                preview = self.build_sync_preview(settings, glooko_client)
                log.append(format_preview_diagnostics(preview, dry_run=True))
            except Exception as exc:
                log.append("=== Mock sync preview (not uploaded) ===")
                log.append("FAILED")
                log.append(str(exc))

        return ConnectionTestResult(
            glooko_ok=glooko_ok,
            nightscout_ok=nightscout_ok,
            glooko_error=glooko_error,
            nightscout_error=nightscout_error,
            diagnostics="\n".join(log).rstrip(),
            sync_preview=preview,
        )

    def run_sync(self, settings: AppSettings) -> SyncResult:
        if not (
            settings.glooko_email
            and settings.glooko_password
            and settings.nightscout_url
            and settings.nightscout_secret
        ):
            return SyncResult(success=False, error="Credentials are not configured")

        log: list[str] = []
        prepared: SyncPreview | None = None
        try:
            log.append("=== Glooko ===")
            log.append(f"Authenticating as {settings.glooko_email}...")
            glooko = GlookoClient(settings.glooko_email, settings.glooko_password)
            summary = glooko.session_summary()
            if summary:
                log.append("Session:")
                log.append(summary)
            log.append("Fetching graph data and device settings...")
            prepared = self.build_sync_preview(settings, glooko)
            log.append(f"OK — {len(prepared.devices)} device(s) found")
            log.append("")
            log.append(format_preview_diagnostics(prepared, dry_run=False))

            log.append("")
            log.append("=== Gluroo / Nightscout ===")
            log.append(f"URL: {settings.nightscout_url}")
            auth = "token query param" if settings.use_token_auth else "api-secret header (SHA1)"
            log.append(f"Auth: {auth}")
            ns = NightscoutClient(
                settings.nightscout_url,
                settings.nightscout_secret,
                settings.use_token_auth,
            )
            status = ns.test_connection()
            log.append("Status check OK")
            log.append(status)

            treatments = prepared.treatments_to_upload
            if not treatments:
                log.append("")
                log.append("Nothing new to upload — sync finished")
                existing = self.state.get()
                existing.last_error = None
                existing.last_boluses_uploaded = 0
                self.state.upsert(existing)
                return SyncResult(
                    success=True,
                    boluses_uploaded=0,
                    pump_note_uploaded=False,
                    devices=prepared.devices,
                    pump_statistics=prepared.pump_statistics,
                    sync_preview=prepared,
                    diagnostics="\n".join(log).rstrip(),
                )

            log.append("")
            report = ns.post_treatments(treatments)
            log.append(report.format_diagnostics())

            uploaded_at = int(datetime.now(timezone.utc).timestamp() * 1000)
            new_bolus = [t for t in treatments if t.event_type.endswith("Bolus")]
            pump_note = next((t for t in treatments if tm.is_pump_mode_note(t)), None)
            records = [
                (tm.deduplication_key(t), t.event_type, t.created_at, uploaded_at)
                for t in treatments
            ]
            self.dedupe.insert_keys(records)

            log.append("")
            log.append("=== Sync complete ===")
            log.append(f"Uploaded {report.uploaded_count} treatment(s)")

            existing = self.state.get()
            existing.last_successful_sync_epoch_ms = int(
                prepared.sync_window_end.timestamp() * 1000
            )
            if pump_note is not None:
                existing.last_pump_mode_note = pump_note.notes
            existing.last_error = None
            existing.last_boluses_uploaded = len(new_bolus)
            self.state.upsert(existing)

            return SyncResult(
                success=True,
                boluses_uploaded=len(new_bolus),
                pump_note_uploaded=pump_note is not None,
                devices=prepared.devices,
                pump_statistics=prepared.pump_statistics,
                sync_preview=prepared,
                diagnostics="\n".join(log).rstrip(),
            )
        except Exception as exc:
            error_text = str(exc)
            log.append("")
            log.append("FAILED")
            log.append(error_text)
            existing = self.state.get()
            existing.last_error = error_text
            self.state.upsert(existing)
            return SyncResult(
                success=False,
                error=error_text,
                sync_preview=prepared,
                diagnostics="\n".join(log).rstrip(),
            )
