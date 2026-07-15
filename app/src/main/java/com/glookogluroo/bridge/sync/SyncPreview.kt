package com.glookogluroo.bridge.sync

import com.glookogluroo.bridge.glooko.DeviceInfo
import com.glookogluroo.bridge.glooko.PumpStatistics
import com.glookogluroo.bridge.nightscout.NightscoutTreatment
import com.glookogluroo.bridge.nightscout.TreatmentMapper
import com.glookogluroo.bridge.util.JsonFormatter
import java.time.Instant

data class SyncPreview(
    val syncWindowStart: Instant,
    val syncWindowEnd: Instant,
    val windowSource: String,
    val bolusesFound: Int,
    val bolusesAlreadySynced: Int,
    val treatmentsToUpload: List<NightscoutTreatment>,
    val devices: List<DeviceInfo> = emptyList(),
    val pumpStatistics: PumpStatistics? = null,
    val jsonPayload: String = "",
    val jitterInsulinTimestamps: Boolean = false,
    /** When set (cloud path), overrides [treatmentsToUpload].size for UI counts. */
    val cloudTreatmentCount: Int? = null,
) {
    val bolusesToUpload: Int
        get() = cloudTreatmentCount
            ?: treatmentsToUpload.count { it.eventType.endsWith("Bolus") }

    val pumpNoteToUpload: Boolean
        get() = treatmentsToUpload.any { TreatmentMapper.isPumpModeNote(it) }

    fun formatDiagnostics(dryRun: Boolean = true): String = buildString {
        appendLine(
            if (dryRun) {
                "=== Mock sync preview (not uploaded) ==="
            } else {
                "=== Sync upload plan ==="
            },
        )
        appendLine("Window: $syncWindowStart → $syncWindowEnd")
        appendLine("Source: $windowSource")
        if (jitterInsulinTimestamps) {
            appendLine("Testing: random microsecond jitter applied to insulin created_at")
        }
        appendLine("Boluses in window: $bolusesFound")
        appendLine("Already synced (skipped): $bolusesAlreadySynced")
        val uploadCount = cloudTreatmentCount ?: treatmentsToUpload.size
        appendLine("Would upload: $uploadCount treatment(s)")
        if (uploadCount == 0 && treatmentsToUpload.isEmpty()) {
            appendLine("Nothing new to upload.")
            return@buildString
        }
        if (treatmentsToUpload.isEmpty() && jsonPayload.isNotBlank() && jsonPayload != "[]") {
            appendLine()
            appendLine("POST /api/v1/treatments body:")
            append(JsonFormatter.prettify(jsonPayload))
            return@buildString
        }
        if (treatmentsToUpload.isEmpty()) {
            return@buildString
        }
        appendLine()
        treatmentsToUpload.forEachIndexed { index, treatment ->
            appendLine("${index + 1}. ${formatTreatmentLine(treatment)}")
            treatment.notes?.takeIf { it.isNotBlank() }?.let { appendLine("   notes: $it") }
        }
        appendLine()
        appendLine("POST /api/v1/treatments body:")
        append(JsonFormatter.prettify(jsonPayload))
    }.trimEnd()

    companion object {
        fun formatTreatmentLine(treatment: NightscoutTreatment): String {
            return buildString {
                append(treatment.eventType)
                append(" @ ${treatment.createdAt}")
                treatment.insulin?.let { append(" | ${trimDouble(it)}u insulin") }
                treatment.carbs?.let { append(" | ${trimDouble(it)}g carbs") }
            }
        }

        private fun trimDouble(value: Double): String {
            return if (value == value.toLong().toDouble()) {
                value.toLong().toString()
            } else {
                "%.2f".format(value)
            }
        }
    }
}
