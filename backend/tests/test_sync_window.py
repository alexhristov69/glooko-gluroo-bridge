"""Parity tests for SyncWindowResolver."""

from datetime import datetime, timezone
from zoneinfo import ZoneInfo

from g2g.models import AppSettings, SyncState
from g2g.sync_window import is_valid_custom_start_input, resolve


def test_backfill_when_never_synced():
    settings = AppSettings(backfill_days=7)
    state = SyncState(last_successful_sync_epoch_ms=0)
    now = datetime(2026, 7, 8, 12, 0, tzinfo=timezone.utc)
    window = resolve(settings, state, now, ZoneInfo("UTC"))
    assert (now - window.start).days == 7
    assert "backfill" in window.source


def test_incremental_with_overlap():
    settings = AppSettings()
    last = datetime(2026, 7, 8, 10, 0, tzinfo=timezone.utc)
    state = SyncState(last_successful_sync_epoch_ms=int(last.timestamp() * 1000))
    now = datetime(2026, 7, 8, 12, 0, tzinfo=timezone.utc)
    window = resolve(settings, state, now, ZoneInfo("UTC"))
    assert window.start == datetime(2026, 7, 8, 8, 0, tzinfo=timezone.utc)
    assert "incremental" in window.source


def test_custom_start():
    settings = AppSettings(sync_from_override="2026-07-01 08:00")
    state = SyncState(last_successful_sync_epoch_ms=0)
    now = datetime(2026, 7, 8, 12, 0, tzinfo=timezone.utc)
    window = resolve(settings, state, now, ZoneInfo("UTC"))
    assert window.start == datetime(2026, 7, 1, 8, 0, tzinfo=timezone.utc)
    assert "custom start" in window.source


def test_invalid_custom_start():
    assert is_valid_custom_start_input("")
    assert is_valid_custom_start_input("2026-07-01 08:00")
    assert not is_valid_custom_start_input("not-a-date")
