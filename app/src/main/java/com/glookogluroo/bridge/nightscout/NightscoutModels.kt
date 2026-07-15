package com.glookogluroo.bridge.nightscout

import com.glookogluroo.bridge.util.JsonFormatter
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class NightscoutTreatment(
    val eventType: String,
    val insulin: Double? = null,
    val carbs: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val enteredBy: String = ENTERED_BY,
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String,
) {
    companion object {
        const val ENTERED_BY = "g2gv000001"
    }
}

object NightscoutJson {
    val instance = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encodeTreatments(treatments: List<NightscoutTreatment>): String {
        return instance.encodeToString(treatments)
    }
}

data class NightscoutBatchResponse(
    val batchIndex: Int,
    val batchSize: Int,
    val httpCode: Int,
    val requestUrl: String,
    val requestBody: String,
    val responseBody: String,
)

data class NightscoutUploadReport(
    val uploadedCount: Int,
    val batches: List<NightscoutBatchResponse>,
) {
    fun formatDiagnostics(): String = buildString {
        appendLine("=== Nightscout upload responses ===")
        if (batches.isEmpty()) {
            appendLine("(no batches sent)")
            return@buildString
        }
        batches.forEach { batch ->
            appendLine()
            appendLine("Batch ${batch.batchIndex + 1}/${batches.size}: ${batch.batchSize} treatment(s)")
            appendLine("POST ${batch.requestUrl}")
            appendLine("HTTP ${batch.httpCode}")
            appendLine("Request body:")
            appendLine(JsonFormatter.prettify(batch.requestBody))
            appendLine("Response body:")
            val response = batch.responseBody.ifBlank { "(empty)" }
            appendLine(if (response == "(empty)") response else JsonFormatter.prettify(response))
        }
    }.trimEnd()
}
