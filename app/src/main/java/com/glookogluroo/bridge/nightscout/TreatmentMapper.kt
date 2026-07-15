package com.glookogluroo.bridge.nightscout

import com.glookogluroo.bridge.glooko.BolusEntry
import com.glookogluroo.bridge.glooko.PumpStatistics
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.random.Random

object TreatmentMapper {
    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    fun bolusToTreatment(bolus: BolusEntry): NightscoutTreatment {
        return NightscoutTreatment(
            eventType = "Correction Bolus",
            insulin = bolus.units,
            carbs = bolus.carbsInput,
            enteredBy = NightscoutTreatment.ENTERED_BY,
            createdAt = isoFormatter.format(bolus.timestamp),
        )
    }

    fun bolusToContextNote(bolus: BolusEntry): NightscoutTreatment? {
        val notes = buildBolusContextNotes(bolus) ?: return null
        return NightscoutTreatment(
            eventType = "Note",
            enteredBy = NightscoutTreatment.ENTERED_BY,
            notes = notes,
            createdAt = isoFormatter.format(bolus.timestamp),
        )
    }

    fun bolusUploadTreatments(
        bolus: BolusEntry,
        jitterTimestamps: Boolean = false,
    ): List<NightscoutTreatment> {
        var bolusTreatment = bolusToTreatment(bolus)
        var contextNote = bolusToContextNote(bolus)
        if (jitterTimestamps) {
            bolusTreatment = applyInsulinTimestampJitter(bolusTreatment)
        }
        contextNote = contextNote?.copy(createdAt = oneSecondAfter(bolusTreatment.createdAt))
        return buildList {
            add(bolusTreatment)
            contextNote?.let { add(it) }
        }
    }

    fun isPumpModeNote(treatment: NightscoutTreatment): Boolean {
        return treatment.eventType == "Note" &&
            treatment.notes?.startsWith("Pump mode:") == true
    }

    fun applyInsulinTimestampJitter(
        treatment: NightscoutTreatment,
        jitterMicros: Long? = null,
    ): NightscoutTreatment {
        if (!treatment.eventType.endsWith("Bolus")) return treatment

        val micros = jitterMicros ?: randomJitterMicros()
        val instant = Instant.parse(treatment.createdAt)
        val jitteredAt = isoFormatter.format(instant.plusNanos(micros * 1_000))
        return treatment.copy(createdAt = jitteredAt)
    }

    private fun randomJitterMicros(): Long {
        var micros = Random.nextLong(-999_999, 1_000_000)
        if (micros == 0L) {
            micros = if (Random.nextBoolean()) 1 else -1
        }
        return micros
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
            enteredBy = NightscoutTreatment.ENTERED_BY,
            notes = notes,
            createdAt = isoFormatter.format(timestamp),
        )
    }

    fun deduplicationKey(treatment: NightscoutTreatment): String {
        val insulin = treatment.insulin ?: 0.0
        val carbs = treatment.carbs ?: 0.0
        return "${treatment.eventType}|${treatment.createdAt}|$insulin|$carbs|${treatment.notes.orEmpty()}"
    }

    private fun oneSecondAfter(createdAt: String): String {
        return isoFormatter.format(Instant.parse(createdAt).plusSeconds(1))
    }

    private fun buildBolusContextNotes(bolus: BolusEntry): String? {
        val parts = buildList {
            bolus.insulinOnBoard?.let { add("IOB: ${"%.2f".format(it)}u") }
            bolus.bloodGlucoseInput?.let { bg ->
                val source = bolus.bloodGlucoseSource?.takeIf { it.isNotBlank() } ?: "pump"
                add("CGM: $bg ($source)")
            }
        }
        return parts.joinToString(" | ").ifBlank { null }
    }
}
