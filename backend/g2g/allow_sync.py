"""Global sync allow-check: kill switch + 24h circuit-breaker override."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any


def parse_iso(value: str | None) -> datetime | None:
    if not value or not str(value).strip():
        return None
    text = str(value).strip().replace("Z", "+00:00")
    try:
        return datetime.fromisoformat(text)
    except ValueError:
        return None


def is_sync_allowed(global_params: dict[str, Any], now: datetime | None = None) -> bool:
    """Return True if sync may proceed.

    Override takes precedence: if now < override_until, allow even when tripped.
    Otherwise require sync_enabled == 'true'.
    """
    now = now or datetime.now(timezone.utc)
    override_until = parse_iso(global_params.get("circuit_breaker_override_until"))
    if override_until is not None and now < override_until:
        return True
    return str(global_params.get("sync_enabled", "true")).lower() == "true"


def circuit_breaker_status(global_params: dict[str, Any], now: datetime | None = None) -> dict[str, Any]:
    now = now or datetime.now(timezone.utc)
    override_until = parse_iso(global_params.get("circuit_breaker_override_until"))
    override_active = override_until is not None and now < override_until
    sync_enabled = str(global_params.get("sync_enabled", "true")).lower() == "true"
    tripped_at = global_params.get("circuit_breaker_tripped_at") or ""
    return {
        "syncEnabled": sync_enabled,
        "syncAllowed": is_sync_allowed(global_params, now),
        "circuitBreakerTripped": bool(tripped_at) and not sync_enabled,
        "trippedAt": tripped_at or None,
        "trippedReason": global_params.get("circuit_breaker_tripped_reason") or None,
        "overrideUntil": override_until.isoformat().replace("+00:00", "Z") if override_until else None,
        "overrideActive": override_active,
        "syncPaused": not is_sync_allowed(global_params, now),
    }
