"""Bolus → Nightscout treatment mapping — ported from TreatmentMapper.kt."""

from __future__ import annotations

import random
from datetime import datetime, timedelta, timezone

from g2g.models import BolusEntry, NightscoutTreatment, PumpStatistics


def _iso(dt: datetime) -> str:
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def bolus_to_treatment(bolus: BolusEntry) -> NightscoutTreatment:
    return NightscoutTreatment(
        event_type="Correction Bolus",
        insulin=bolus.units,
        carbs=bolus.carbs_input,
        entered_by=NightscoutTreatment.ENTERED_BY,
        created_at=_iso(bolus.timestamp),
    )


def _build_bolus_context_notes(bolus: BolusEntry) -> str | None:
    parts: list[str] = []
    if bolus.insulin_on_board is not None:
        parts.append(f"IOB: {bolus.insulin_on_board:.2f}u")
    if bolus.blood_glucose_input is not None:
        source = bolus.blood_glucose_source.strip() if bolus.blood_glucose_source else "pump"
        if not source:
            source = "pump"
        parts.append(f"CGM: {bolus.blood_glucose_input} ({source})")
    return " | ".join(parts) if parts else None


def bolus_to_context_note(bolus: BolusEntry) -> NightscoutTreatment | None:
    notes = _build_bolus_context_notes(bolus)
    if not notes:
        return None
    return NightscoutTreatment(
        event_type="Note",
        entered_by=NightscoutTreatment.ENTERED_BY,
        notes=notes,
        created_at=_iso(bolus.timestamp),
    )


def apply_insulin_timestamp_jitter(
    treatment: NightscoutTreatment,
    jitter_micros: int | None = None,
) -> NightscoutTreatment:
    if not treatment.event_type.endswith("Bolus"):
        return treatment
    micros = jitter_micros if jitter_micros is not None else _random_jitter_micros()
    instant = datetime.fromisoformat(treatment.created_at.replace("Z", "+00:00"))
    jittered = instant + timedelta(microseconds=micros)
    return NightscoutTreatment(
        event_type=treatment.event_type,
        insulin=treatment.insulin,
        carbs=treatment.carbs,
        entered_by=treatment.entered_by,
        notes=treatment.notes,
        created_at=_iso(jittered),
    )


def _random_jitter_micros() -> int:
    micros = random.randint(-999_999, 999_999)
    if micros == 0:
        micros = 1 if random.choice([True, False]) else -1
    return micros


def _one_second_after(created_at: str) -> str:
    instant = datetime.fromisoformat(created_at.replace("Z", "+00:00"))
    return _iso(instant + timedelta(seconds=1))


def bolus_upload_treatments(
    bolus: BolusEntry,
    jitter_timestamps: bool = False,
) -> list[NightscoutTreatment]:
    bolus_treatment = bolus_to_treatment(bolus)
    context_note = bolus_to_context_note(bolus)
    if jitter_timestamps:
        bolus_treatment = apply_insulin_timestamp_jitter(bolus_treatment)
    if context_note is not None:
        context_note = NightscoutTreatment(
            event_type=context_note.event_type,
            insulin=context_note.insulin,
            carbs=context_note.carbs,
            entered_by=context_note.entered_by,
            notes=context_note.notes,
            created_at=_one_second_after(bolus_treatment.created_at),
        )
    result = [bolus_treatment]
    if context_note is not None:
        result.append(context_note)
    return result


def is_pump_mode_note(treatment: NightscoutTreatment) -> bool:
    return treatment.event_type == "Note" and (treatment.notes or "").startswith("Pump mode:")


def pump_mode_to_note(
    pump: PumpStatistics,
    timestamp: datetime | None = None,
) -> NightscoutTreatment:
    ts = timestamp or datetime.now(timezone.utc)
    mode_label = pump.mode.value.lower() if pump.mode else "unknown"
    notes = (
        f"Pump mode: {mode_label}"
        f" (auto {int(pump.auto_percentage)}%"
        f", manual {int(pump.manual_percentage)}%"
        f", limited {int(pump.limited_percentage)}%)"
    )
    if pump.total_insulin_per_day is not None:
        notes += f" | {pump.total_insulin_per_day:.1f} u/day"
    return NightscoutTreatment(
        event_type="Note",
        entered_by=NightscoutTreatment.ENTERED_BY,
        notes=notes,
        created_at=_iso(ts),
    )


def deduplication_key(treatment: NightscoutTreatment) -> str:
    insulin = treatment.insulin if treatment.insulin is not None else 0.0
    carbs = treatment.carbs if treatment.carbs is not None else 0.0
    notes = treatment.notes or ""
    return f"{treatment.event_type}|{treatment.created_at}|{insulin}|{carbs}|{notes}"
