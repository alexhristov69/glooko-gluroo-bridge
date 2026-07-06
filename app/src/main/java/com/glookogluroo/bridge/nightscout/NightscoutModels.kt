package com.glookogluroo.bridge.nightscout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class NightscoutTreatment(
    val eventType: String,
    val insulin: Double? = null,
    val carbs: Double? = null,
    @SerialName("created_at") val createdAt: String,
    val enteredBy: String = "glooko-bridge",
    val notes: String? = null,
)

object NightscoutJson {
    val instance = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encodeTreatments(treatments: List<NightscoutTreatment>): String {
        return instance.encodeToString(treatments)
    }
}
