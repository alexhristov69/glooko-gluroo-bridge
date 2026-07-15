"""Circuit breaker allow-sync helper tests."""

from datetime import datetime, timedelta, timezone

from g2g.allow_sync import circuit_breaker_status, is_sync_allowed


def test_enabled_by_default():
    assert is_sync_allowed({"sync_enabled": "true"})


def test_tripped_blocks():
    assert not is_sync_allowed(
        {
            "sync_enabled": "false",
            "circuit_breaker_tripped_at": "2026-07-08T00:00:00Z",
            "circuit_breaker_override_until": "",
        }
    )


def test_override_allows_despite_trip():
    now = datetime(2026, 7, 8, 12, 0, tzinfo=timezone.utc)
    until = (now + timedelta(hours=12)).isoformat().replace("+00:00", "Z")
    assert is_sync_allowed(
        {
            "sync_enabled": "false",
            "circuit_breaker_tripped_at": "2026-07-08T00:00:00Z",
            "circuit_breaker_override_until": until,
        },
        now=now,
    )


def test_expired_override_blocks():
    now = datetime(2026, 7, 8, 12, 0, tzinfo=timezone.utc)
    until = (now - timedelta(hours=1)).isoformat().replace("+00:00", "Z")
    assert not is_sync_allowed(
        {
            "sync_enabled": "false",
            "circuit_breaker_override_until": until,
        },
        now=now,
    )


def test_status_fields():
    status = circuit_breaker_status(
        {
            "sync_enabled": "false",
            "circuit_breaker_tripped_at": "2026-07-08T00:00:00Z",
            "circuit_breaker_tripped_reason": "lambda_invocations_2001",
            "circuit_breaker_override_until": "",
        }
    )
    assert status["syncPaused"] is True
    assert status["circuitBreakerTripped"] is True
    assert status["trippedReason"] == "lambda_invocations_2001"
