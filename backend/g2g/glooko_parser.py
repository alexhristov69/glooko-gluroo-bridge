"""Glooko JSON parsers — ported from GlookoParser.kt."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any
from zoneinfo import ZoneInfo

from g2g.models import BolusEntry, DeviceInfo, PumpMode, PumpStatistics


def parse_timestamp(value: Any, zone: ZoneInfo | None = None) -> datetime | None:
    """Parse Glooko timestamps.

    Strings ending in Z are treated as local wall-clock (not UTC).
    """
    if value is None:
        return None
    zone = zone or ZoneInfo("America/Los_Angeles")

    if isinstance(value, (int, float)):
        epoch = float(value)
        if epoch > 1e12:
            return datetime.fromtimestamp(epoch / 1000.0, tz=timezone.utc)
        return datetime.fromtimestamp(epoch, tz=timezone.utc)

    text = str(value).strip()
    if not text:
        return None

    # Numeric string
    try:
        return parse_timestamp(float(text), zone)
    except ValueError:
        pass

    local_text = text
    treat_as_local = False
    if text.endswith("Z") or text.endswith("z"):
        local_text = text[:-1]
        treat_as_local = True

    formats = (
        "%Y-%m-%dT%H:%M:%S.%f",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%d %H:%M:%S",
    )
    for fmt in formats:
        try:
            naive = datetime.strptime(local_text, fmt)
            if treat_as_local:
                return naive.replace(tzinfo=zone).astimezone(timezone.utc)
            return naive.replace(tzinfo=timezone.utc)
        except ValueError:
            continue

    try:
        # True ISO with offset
        return datetime.fromisoformat(text.replace("Z", "+00:00")).astimezone(timezone.utc)
    except ValueError:
        return None


def _as_float(value: Any) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _as_int(value: Any) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def parse_bolus_entries(graph_data: dict[str, Any], zone: ZoneInfo | None = None) -> list[BolusEntry]:
    series = graph_data.get("series") or graph_data
    if not isinstance(series, dict):
        return []
    items = series.get("deliveredBolus") or []
    entries: list[BolusEntry] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        time_raw = item.get("timestamp", item.get("x"))
        ts = parse_timestamp(time_raw, zone)
        units = _as_float(item.get("insulinDelivered", item.get("y", item.get("value"))))
        if ts is None or units is None or units <= 0:
            continue
        is_manual = item.get("isManual")
        entries.append(
            BolusEntry(
                timestamp=ts,
                units=units,
                carbs_input=_as_float(item.get("carbsInput")),
                insulin_on_board=_as_float(item.get("insulinOnBoard")),
                blood_glucose_input=_as_int(item.get("bloodGlucoseInput")),
                blood_glucose_source=item.get("bloodGlucoseInputSource"),
                correction_units=_as_float(item.get("insulinRecommendationForCorrection")),
                carb_units=_as_float(item.get("insulinRecommendationForCarbs")),
                is_manual=bool(is_manual) if isinstance(is_manual, bool) else False,
                device_name=item.get("deviceName"),
            )
        )
    return entries


def _argmax_mode(auto: float, manual: float, limited: float) -> PumpMode:
    if auto >= manual and auto >= limited:
        return PumpMode.AUTO
    if manual >= limited:
        return PumpMode.MANUAL
    return PumpMode.LIMITED


def parse_pump_mode(statistics: dict[str, Any]) -> PumpStatistics | None:
    if not isinstance(statistics, dict):
        return None

    auto = statistics.get("op5PumpModeAutomaticPercentage")
    manual = statistics.get("op5PumpModeManualPercentage")
    limited = statistics.get("op5PumpModeLimitedPercentage")
    if auto is not None or manual is not None or limited is not None:
        auto_f = _as_float(auto) or 0.0
        manual_f = _as_float(manual) or 0.0
        limited_f = _as_float(limited) or 0.0
        return PumpStatistics(
            mode=_argmax_mode(auto_f, manual_f, limited_f),
            auto_percentage=auto_f,
            manual_percentage=manual_f,
            limited_percentage=limited_f,
            hypo_protect_percentage=_as_float(statistics.get("op5PumpModeHypoProtectPercentage")) or 0.0,
            total_insulin_per_day=_as_float(statistics.get("totalInsulinPerDay")),
            basal_percentage=_as_float(statistics.get("basalPercentage")),
            bolus_percentage=_as_float(statistics.get("bolusPercentage")),
            average_bg=_as_float(statistics.get("averageBg")),
            in_range_percentage=_as_float(statistics.get("inRangePercentage")),
            carbs_per_day=_as_float(statistics.get("carbsPerDay")),
        )

    modes = statistics.get("pumpModes") or statistics.get("pump_modes")
    if isinstance(modes, dict):
        auto_f = _as_float(modes.get("auto", modes.get("automated"))) or 0.0
        manual_f = _as_float(modes.get("manual")) or 0.0
        limited_f = _as_float(modes.get("limited")) or 0.0
        if auto_f or manual_f or limited_f:
            return PumpStatistics(
                mode=_argmax_mode(auto_f, manual_f, limited_f),
                auto_percentage=auto_f,
                manual_percentage=manual_f,
                limited_percentage=limited_f,
            )
    return None


def parse_devices(device_data: dict[str, Any], zone: ZoneInfo | None = None) -> list[DeviceInfo]:
    if not isinstance(device_data, dict):
        return []
    devices_raw = device_data.get("devices") or []
    devices: list[DeviceInfo] = []
    for item in devices_raw:
        if not isinstance(item, dict):
            continue
        name = item.get("displayName") or item.get("shortDisplayName") or "Unknown"
        props_raw = item.get("properties") or {}
        props = {str(k): str(v) for k, v in props_raw.items()} if isinstance(props_raw, dict) else {}
        devices.append(
            DeviceInfo(
                name=name,
                brand=item.get("brand"),
                model=item.get("model"),
                serial_number=item.get("serialNumber"),
                device_type=item.get("type"),
                last_sync=parse_timestamp(item.get("lastSyncTimestamp"), zone),
                properties=props,
            )
        )
    return devices
