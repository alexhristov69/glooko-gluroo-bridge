package com.glookogluroo.bridge.nightscout

import com.glookogluroo.bridge.glooko.BolusEntry
import com.glookogluroo.bridge.glooko.PumpStatistics
import java.time.Instant
import java.time.format.DateTimeFormatter

object TreatmentMapper {
    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    fun bolusToTreatment(bolus: BolusEntry): NightscoutTreatment {
        val eventType = if ((bolus.carbsInput ?: 0.0) > 0.0) "Meal Bolus" else "Correction Bolus"
        return NightscoutTreatment(
            eventType = eventType,
            insulin = bolus.units,
            carbs = bolus.carbsInput,
            createdAt = isoFormatter.format(bolus.timestamp),
            notes = buildBolusNotes(bolus),
        )
    }

    fun pumpModeToNote(pump: PumpStatistics, timestamp: Instant = Instant.now()): NightscoutTreatment {
        val modeLabel = pump.mode?.name?.lowercase() ?: "unknown"
        val notes = buildString {
            append("Pump mode: $modeLabel")
            append(" (auto ${pump.autoPercentage.toInt()}%")
            append(", manual ${pump.manualPercentage.toInt()}%")
            append(", limited ${pump.limitedPercentage.toInt()}%)")
            pump.totalInsulinPerDay?.let { append(" | ${"%.1f".format(it)} u/day") }
        }
        return NightscoutTreatment(
            eventType = "Note",
            createdAt = isoFormatter.format(timestamp),
            notes = notes,
        )
    }

    fun deduplicationKey(treatment: NightscoutTreatment): String {
        val insulin = treatment.insulin ?: 0.0
        val carbs = treatment.carbs ?: 0.0
        return "${treatment.eventType}|${treatment.createdAt}|$insulin|$carbs|${treatment.notes.orEmpty()}"
    }

    private fun buildBolusNotes(bolus: BolusEntry): String {
        return buildList {
            bolus.insulinOnBoard?.let { add("IOB: ${"%.2f".format(it)}u") }
            bolus.deviceName?.let { add("Device: $it") }
            if (bolus.isManual) add("Manual bolus")
            bolus.bloodGlucoseInput?.let { add("BG: $it (${bolus.bloodGlucoseSource ?: "unknown"})") }
        }.joinToString(" | ").ifBlank { "Imported from Glooko" }
    }
}
