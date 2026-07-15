"""Parity tests for GlookoParser — mirrors GlookoParserTest.kt."""

from datetime import datetime
from zoneinfo import ZoneInfo

from g2g.glooko_parser import parse_bolus_entries, parse_devices, parse_pump_mode, parse_timestamp
from g2g.models import PumpMode


def test_parse_timestamp_treats_glooko_z_as_local_wall_clock():
    pacific = ZoneInfo("America/Los_Angeles")
    instant = parse_timestamp("2026-07-06T15:00:00.000Z", pacific)
    assert instant == datetime.fromisoformat("2026-07-06T22:00:00+00:00")


def test_parse_bolus_entries_extracts_delivered_boluses():
    graph_data = {
        "series": {
            "deliveredBolus": [
                {
                    "timestamp": "2026-03-29T10:00:00.000Z",
                    "insulinDelivered": 2.5,
                    "carbsInput": 30.0,
                    "insulinOnBoard": 0.8,
                    "deviceName": "Omnipod 5",
                },
                {"timestamp": "2026-03-29T12:00:00.000Z", "y": 1.0},
            ]
        }
    }
    entries = parse_bolus_entries(graph_data, ZoneInfo("UTC"))
    assert len(entries) == 2
    assert abs(entries[0].units - 2.5) < 0.001
    assert entries[0].carbs_input == 30.0
    assert entries[0].device_name == "Omnipod 5"


def test_parse_pump_mode_reads_op5_flat_keys():
    statistics = {
        "op5PumpModeAutomaticPercentage": 85.0,
        "op5PumpModeManualPercentage": 10.0,
        "op5PumpModeLimitedPercentage": 5.0,
        "totalInsulinPerDay": 22.5,
    }
    pump = parse_pump_mode(statistics)
    assert pump is not None
    assert pump.mode == PumpMode.AUTO
    assert pump.auto_percentage == 85.0
    assert pump.total_insulin_per_day == 22.5


def test_parse_pump_mode_returns_null_when_missing():
    assert parse_pump_mode({}) is None


def test_parse_devices_reads_connected_devices():
    device_data = {
        "devices": [
            {
                "displayName": "Insulet Omnipod 5 System",
                "brand": "Insulet",
                "type": "pump",
                "lastSyncTimestamp": "2026-03-29T10:00:00.000Z",
            }
        ]
    }
    devices = parse_devices(device_data, ZoneInfo("UTC"))
    assert len(devices) == 1
    assert devices[0].name == "Insulet Omnipod 5 System"
    assert devices[0].device_type == "pump"
    assert devices[0].last_sync is not None
