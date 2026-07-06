package com.glookogluroo.bridge.glooko

import java.time.Instant

data class BolusEntry(
    val timestamp: Instant,
    val units: Double,
    val carbsInput: Double? = null,
    val insulinOnBoard: Double? = null,
    val bloodGlucoseInput: Int? = null,
    val bloodGlucoseSource: String? = null,
    val correctionUnits: Double? = null,
    val carbUnits: Double? = null,
    val isManual: Boolean = false,
    val deviceName: String? = null,
)

enum class PumpMode {
    AUTO,
    MANUAL,
    LIMITED,
}

data class PumpStatistics(
    val mode: PumpMode?,
    val autoPercentage: Double,
    val manualPercentage: Double,
    val limitedPercentage: Double,
    val hypoProtectPercentage: Double = 0.0,
    val totalInsulinPerDay: Double? = null,
    val basalPercentage: Double? = null,
    val bolusPercentage: Double? = null,
    val averageBg: Double? = null,
    val inRangePercentage: Double? = null,
    val carbsPerDay: Double? = null,
)

data class DeviceInfo(
    val name: String,
    val brand: String? = null,
    val model: String? = null,
    val serialNumber: String? = null,
    val deviceType: String? = null,
    val lastSync: Instant? = null,
    val properties: Map<String, String> = emptyMap(),
)
