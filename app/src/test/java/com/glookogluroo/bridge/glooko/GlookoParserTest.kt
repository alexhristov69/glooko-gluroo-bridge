package com.glookogluroo.bridge.glooko

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class GlookoParserTest {
    @Test
    fun parseTimestamp_treatsGlookoZSuffixAsLocalWallClock() {
        val pacific = ZoneId.of("America/Los_Angeles")
        val instant = GlookoParser.parseTimestamp("2026-07-06T15:00:00.000Z", pacific)

        assertEquals(Instant.parse("2026-07-06T22:00:00.000Z"), instant)
    }

    @Test
    fun parseBolusEntries_extractsDeliveredBoluses() {
        val graphData = buildJsonObject {
            putJsonObject("series") {
                putJsonArray("deliveredBolus") {
                    add(
                        buildJsonObject {
                            put("timestamp", "2026-03-29T10:00:00.000Z")
                            put("insulinDelivered", 2.5)
                            put("carbsInput", 30.0)
                            put("insulinOnBoard", 0.8)
                            put("deviceName", "Omnipod 5")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("timestamp", "2026-03-29T12:00:00.000Z")
                            put("y", 1.0)
                        },
                    )
                }
            }
        }

        val entries = GlookoParser.parseBolusEntries(graphData)
        assertEquals(2, entries.size)
        assertEquals(2.5, entries.first().units, 0.001)
        assertEquals(30.0, entries.first().carbsInput)
        assertEquals("Omnipod 5", entries.first().deviceName)
    }

    @Test
    fun parsePumpMode_readsOp5FlatKeys() {
        val statistics = buildJsonObject {
            put("op5PumpModeAutomaticPercentage", 85.0)
            put("op5PumpModeManualPercentage", 10.0)
            put("op5PumpModeLimitedPercentage", 5.0)
            put("totalInsulinPerDay", 22.5)
        }

        val pump = GlookoParser.parsePumpMode(statistics)
        assertNotNull(pump)
        assertEquals(PumpMode.AUTO, pump?.mode)
        assertEquals(85.0, pump?.autoPercentage)
        assertEquals(22.5, pump?.totalInsulinPerDay)
    }

    @Test
    fun parsePumpMode_returnsNullWhenMissing() {
        assertNull(GlookoParser.parsePumpMode(buildJsonObject {}))
    }

    @Test
    fun parseDevices_readsConnectedDevices() {
        val deviceData = buildJsonObject {
            putJsonArray("devices") {
                add(
                    buildJsonObject {
                        put("displayName", "Insulet Omnipod 5 System")
                        put("brand", "Insulet")
                        put("type", "pump")
                        put("lastSyncTimestamp", "2026-03-29T10:00:00.000Z")
                    },
                )
            }
        }

        val devices = GlookoParser.parseDevices(deviceData)
        assertEquals(1, devices.size)
        assertEquals("Insulet Omnipod 5 System", devices.first().name)
        assertEquals("pump", devices.first().deviceType)
        assertNotNull(devices.first().lastSync)
    }
}
