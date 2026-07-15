"""Parity tests for TreatmentMapper."""

from datetime import datetime, timezone

from g2g.models import BolusEntry, NightscoutTreatment, PumpMode, PumpStatistics
from g2g.treatment_mapper import (
    apply_insulin_timestamp_jitter,
    bolus_to_context_note,
    bolus_to_treatment,
    bolus_upload_treatments,
    deduplication_key,
    is_pump_mode_note,
    pump_mode_to_note,
)


def test_bolus_to_treatment():
    bolus = BolusEntry(
        timestamp=datetime(2026, 3, 29, 10, 0, tzinfo=timezone.utc),
        units=2.5,
        carbs_input=30.0,
    )
    t = bolus_to_treatment(bolus)
    assert t.event_type == "Correction Bolus"
    assert t.insulin == 2.5
    assert t.carbs == 30.0
    assert t.entered_by == "g2gv000001"


def test_context_note_format():
    bolus = BolusEntry(
        timestamp=datetime(2026, 3, 29, 10, 0, tzinfo=timezone.utc),
        units=1.0,
        insulin_on_board=0.8,
        blood_glucose_input=142,
        blood_glucose_source="CGM",
    )
    note = bolus_to_context_note(bolus)
    assert note is not None
    assert note.notes == "IOB: 0.80u | CGM: 142 (CGM)"


def test_bolus_upload_note_one_second_after():
    bolus = BolusEntry(
        timestamp=datetime(2026, 3, 29, 10, 0, tzinfo=timezone.utc),
        units=1.0,
        insulin_on_board=0.5,
    )
    uploads = bolus_upload_treatments(bolus)
    assert len(uploads) == 2
    assert uploads[0].created_at.startswith("2026-03-29T10:00:00")
    assert "T10:00:01" in uploads[1].created_at or uploads[1].created_at.endswith("10:00:01Z")


def test_jitter_micros():
    t = NightscoutTreatment(
        event_type="Correction Bolus",
        insulin=1.0,
        created_at="2026-03-29T10:00:00.000Z",
    )
    jittered = apply_insulin_timestamp_jitter(t, jitter_micros=25)
    assert "000025" in jittered.created_at or jittered.created_at.endswith(".000025Z")


def test_pump_mode_note():
    pump = PumpStatistics(
        mode=PumpMode.AUTO,
        auto_percentage=85.0,
        manual_percentage=10.0,
        limited_percentage=5.0,
        total_insulin_per_day=22.5,
    )
    note = pump_mode_to_note(pump, datetime(2026, 3, 29, tzinfo=timezone.utc))
    assert is_pump_mode_note(note)
    assert "Pump mode: auto" in (note.notes or "")
    assert "22.5 u/day" in (note.notes or "")


def test_deduplication_key():
    t = NightscoutTreatment(
        event_type="Correction Bolus",
        insulin=2.5,
        carbs=30.0,
        created_at="2026-03-29T10:00:00Z",
    )
    assert deduplication_key(t) == "Correction Bolus|2026-03-29T10:00:00Z|2.5|30.0|"
