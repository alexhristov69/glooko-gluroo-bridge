package com.glookogluroo.bridge.glooko

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object GlookoParser {
    private val json = Json { ignoreUnknownKeys = true }

    private val glookoLocalDateTimeFormatters = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
    )

    fun parseTimestamp(value: JsonElement?, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        if (value == null) return null
        return when {
            value.jsonPrimitive.isString -> parseTimestampString(value.jsonPrimitive.content, zone)
            value.jsonPrimitive.content.toDoubleOrNull() != null -> {
                val epoch = value.jsonPrimitive.double
                if (epoch > 1e12) Instant.ofEpochMilli(epoch.toLong())
                else Instant.ofEpochSecond(epoch.toLong())
            }
            else -> null
        }
    }

    fun parseTimestamp(value: String?, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        if (value.isNullOrBlank()) return null
        return parseTimestampString(value, zone)
    }

    private fun parseTimestampString(value: String, zone: ZoneId): Instant? {
        // Glooko pump graph timestamps are local wall-clock times with a literal "Z" suffix,
        // not true UTC. Match glooko-reader's naive-datetime parsing, then apply the account zone.
        if (value.endsWith("Z", ignoreCase = true)) {
            parseLocalDateTime(value.dropLast(1).trim())?.let { local ->
                return local.atZone(zone).toInstant()
            }
        }

        parseLocalDateTime(value)?.let { local ->
            return local.atZone(zone).toInstant()
        }

        runCatching {
            return Instant.parse(value)
        }

        return null
    }

    private fun parseLocalDateTime(value: String): LocalDateTime? {
        for (formatter in glookoLocalDateTimeFormatters) {
            runCatching {
                return LocalDateTime.parse(value, formatter)
            }
        }
        runCatching {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        }
        return null
    }

    fun parseBolusEntries(graphData: JsonObject): List<BolusEntry> {
        val series = graphData["series"]?.jsonObject ?: graphData
        val deliveredBolus = series["deliveredBolus"]?.jsonArray ?: return emptyList()

        return deliveredBolus.mapNotNull { element ->
            val bolus = element.jsonObject
            val timestamp = parseTimestamp(bolus["timestamp"]) ?: parseTimestamp(bolus["x"])
                ?: return@mapNotNull null

            val units = bolus["insulinDelivered"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: bolus["y"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: bolus["value"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: return@mapNotNull null

            if (units <= 0) return@mapNotNull null

            BolusEntry(
                timestamp = timestamp,
                units = units,
                carbsInput = bolus["carbsInput"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                insulinOnBoard = bolus["insulinOnBoard"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                bloodGlucoseInput = bolus["bloodGlucoseInput"]?.jsonPrimitive?.content?.toIntOrNull(),
                bloodGlucoseSource = bolus["bloodGlucoseInputSource"]?.jsonPrimitive?.content,
                correctionUnits = bolus["insulinRecommendationForCorrection"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                carbUnits = bolus["insulinRecommendationForCarbs"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                isManual = bolus["isManual"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                deviceName = bolus["deviceName"]?.jsonPrimitive?.content,
            )
        }
    }

    fun parsePumpMode(statistics: JsonObject): PumpStatistics? {
        val autoPct = statistics["op5PumpModeAutomaticPercentage"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val manualPct = statistics["op5PumpModeManualPercentage"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val limitedPct = statistics["op5PumpModeLimitedPercentage"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val hypoPct = statistics["op5PumpModeHypoProtectPercentage"]?.jsonPrimitive?.content?.toDoubleOrNull()

        if (autoPct != null || manualPct != null) {
            val auto = autoPct ?: 0.0
            val manual = manualPct ?: 0.0
            val limited = limitedPct ?: 0.0
            val mode = when {
                auto >= manual && auto >= limited -> PumpMode.AUTO
                manual >= limited -> PumpMode.MANUAL
                else -> PumpMode.LIMITED
            }
            return PumpStatistics(
                mode = mode,
                autoPercentage = auto,
                manualPercentage = manual,
                limitedPercentage = limited,
                hypoProtectPercentage = hypoPct ?: 0.0,
                totalInsulinPerDay = statistics["totalInsulinPerDay"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                basalPercentage = statistics["basalPercentage"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                bolusPercentage = statistics["bolusPercentage"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                averageBg = statistics["averageBg"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                inRangePercentage = statistics["inRangePercentage"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                carbsPerDay = statistics["carbsPerDay"]?.jsonPrimitive?.content?.toDoubleOrNull(),
            )
        }

        val pumpModes = statistics["pumpModes"]?.jsonObject
            ?: statistics["pump_modes"]?.jsonObject
            ?: return null

        val auto = pumpModes["auto"]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: pumpModes["automated"]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: 0.0
        val manual = pumpModes["manual"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        val limited = pumpModes["limited"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        val mode = when {
            auto >= manual && auto >= limited -> PumpMode.AUTO
            manual >= limited -> PumpMode.MANUAL
            else -> PumpMode.LIMITED
        }

        return PumpStatistics(
            mode = mode,
            autoPercentage = auto,
            manualPercentage = manual,
            limitedPercentage = limited,
        )
    }

    fun parseDevices(deviceData: JsonObject): List<DeviceInfo> {
        val devices = deviceData["devices"]?.jsonArray ?: return emptyList()
        return devices.mapNotNull { element ->
            val dev = element.jsonObject
            val properties = dev["properties"]?.jsonObject?.entries?.associate { (key, value) ->
                key to value.jsonPrimitive.content
            } ?: emptyMap()

            DeviceInfo(
                name = dev["displayName"]?.jsonPrimitive?.content
                    ?: dev["shortDisplayName"]?.jsonPrimitive?.content
                    ?: "Unknown",
                brand = dev["brand"]?.jsonPrimitive?.content,
                model = dev["model"]?.jsonPrimitive?.content,
                serialNumber = dev["serialNumber"]?.jsonPrimitive?.content,
                deviceType = dev["type"]?.jsonPrimitive?.content,
                lastSync = parseTimestamp(dev["lastSyncTimestamp"]),
                properties = properties,
            )
        }
    }

    fun parseJsonObject(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject
}
