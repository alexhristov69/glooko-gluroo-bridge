"""Sync window resolution — ported from SyncWindow.kt."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from g2g.models import AppSettings, SyncState, SyncWindow

LOCAL_INPUT_FMT = "%Y-%m-%d %H:%M"


def parse_custom_start(value: str, zone: ZoneInfo | None = None) -> datetime | None:
    if not value or not value.strip():
        return None
    zone = zone or ZoneInfo("America/Los_Angeles")
    try:
        naive = datetime.strptime(value.strip(), LOCAL_INPUT_FMT)
        return naive.replace(tzinfo=zone).astimezone(timezone.utc)
    except ValueError:
        return None


def format_local(instant: datetime, zone: ZoneInfo | None = None) -> str:
    zone = zone or ZoneInfo("America/Los_Angeles")
    return instant.astimezone(zone).strftime(LOCAL_INPUT_FMT)


def is_valid_custom_start_input(value: str, zone: ZoneInfo | None = None) -> bool:
    return (not value or not value.strip()) or parse_custom_start(value, zone) is not None


def resolve(
    settings: AppSettings,
    sync_state: SyncState,
    now: datetime | None = None,
    zone: ZoneInfo | None = None,
) -> SyncWindow:
    now = now or datetime.now(timezone.utc)
    zone = zone or ZoneInfo("America/Los_Angeles")

    custom = parse_custom_start(settings.sync_from_override, zone)
    if custom is not None:
        clamped = now if custom > now else custom
        return SyncWindow(
            start=clamped,
            end=now,
            source=f"custom start ({format_local(clamped, zone)})",
        )

    if sync_state.last_successful_sync_epoch_ms == 0:
        start = now - timedelta(days=settings.backfill_days)
        return SyncWindow(
            start=start,
            end=now,
            source=f"backfill ({settings.backfill_days} days, no prior sync)",
        )

    last_sync = datetime.fromtimestamp(
        sync_state.last_successful_sync_epoch_ms / 1000.0,
        tz=timezone.utc,
    )
    start = last_sync - timedelta(hours=2)
    return SyncWindow(
        start=start,
        end=now,
        source=f"incremental (last sync {format_local(last_sync, zone)} − 2h overlap)",
    )
