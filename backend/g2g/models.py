"""Shared domain models for the sync engine."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any


class PumpMode(str, Enum):
    AUTO = "AUTO"
    MANUAL = "MANUAL"
    LIMITED = "LIMITED"


@dataclass
class BolusEntry:
    timestamp: datetime
    units: float
    carbs_input: float | None = None
    insulin_on_board: float | None = None
    blood_glucose_input: int | None = None
    blood_glucose_source: str | None = None
    correction_units: float | None = None
    carb_units: float | None = None
    is_manual: bool = False
    device_name: str | None = None


@dataclass
class PumpStatistics:
    mode: PumpMode | None
    auto_percentage: float
    manual_percentage: float
    limited_percentage: float
    hypo_protect_percentage: float = 0.0
    total_insulin_per_day: float | None = None
    basal_percentage: float | None = None
    bolus_percentage: float | None = None
    average_bg: float | None = None
    in_range_percentage: float | None = None
    carbs_per_day: float | None = None


@dataclass
class DeviceInfo:
    name: str
    brand: str | None = None
    model: str | None = None
    serial_number: str | None = None
    device_type: str | None = None
    last_sync: datetime | None = None
    properties: dict[str, str] = field(default_factory=dict)


@dataclass
class NightscoutTreatment:
    event_type: str
    created_at: str
    insulin: float | None = None
    carbs: float | None = None
    entered_by: str = "g2gv000001"
    notes: str | None = None

    ENTERED_BY = "g2gv000001"

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "eventType": self.event_type,
            "enteredBy": self.entered_by,
            "created_at": self.created_at,
        }
        if self.insulin is not None:
            payload["insulin"] = self.insulin
        if self.carbs is not None:
            payload["carbs"] = self.carbs
        if self.notes is not None:
            payload["notes"] = self.notes
        return payload


@dataclass
class AppSettings:
    glooko_email: str = ""
    glooko_password: str = ""
    nightscout_url: str = ""
    nightscout_secret: str = ""
    use_token_auth: bool = False
    sync_enabled: bool = False
    backfill_days: int = 7
    sync_from_override: str = ""
    post_pump_mode_notes: bool = True
    jitter_insulin_timestamps: bool = False
    sync_interval_minutes: int = 15
    # IANA zone for Glooko Z-suffix local wall-clock timestamps (Lambda has no device TZ)
    timezone: str = "America/Los_Angeles"


@dataclass
class SyncState:
    last_successful_sync_epoch_ms: int = 0
    next_scheduled_sync_epoch_ms: int = 0
    last_pump_mode_note: str | None = None
    last_error: str | None = None
    last_boluses_uploaded: int = 0


@dataclass
class SyncWindow:
    start: datetime
    end: datetime
    source: str


@dataclass
class SyncPreview:
    sync_window_start: datetime
    sync_window_end: datetime
    window_source: str
    boluses_found: int
    boluses_already_synced: int
    treatments_to_upload: list[NightscoutTreatment]
    devices: list[DeviceInfo] = field(default_factory=list)
    pump_statistics: PumpStatistics | None = None
    json_payload: str = "[]"
    jitter_insulin_timestamps: bool = False

    @property
    def boluses_to_upload(self) -> int:
        return sum(1 for t in self.treatments_to_upload if t.event_type.endswith("Bolus"))

    @property
    def pump_note_to_upload(self) -> bool:
        return any(
            t.event_type == "Note" and (t.notes or "").startswith("Pump mode:")
            for t in self.treatments_to_upload
        )
