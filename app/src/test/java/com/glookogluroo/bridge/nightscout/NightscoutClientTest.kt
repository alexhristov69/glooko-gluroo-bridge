package com.glookogluroo.bridge.nightscout

import com.glookogluroo.bridge.glooko.BolusEntry
import com.glookogluroo.bridge.glooko.PumpMode
import com.glookogluroo.bridge.glooko.PumpStatistics
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NightscoutClientTest {
    @Test
    fun sha1_matchesKnownValue() {
        val hash = NightscoutClient.sha1("test")
        assertEquals("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3", hash)
    }

    @Test
    fun bolusToContextNote_includesIobAndCgm() {
        val note = TreatmentMapper.bolusToContextNote(
            BolusEntry(
                timestamp = Instant.parse("2026-03-29T10:00:00.000Z"),
                units = 2.0,
                insulinOnBoard = 0.8,
                bloodGlucoseInput = 142,
                bloodGlucoseSource = "CGM",
            ),
        )

        assertNotNull(note)
        assertEquals("Note", note?.eventType)
        assertEquals("IOB: 0.80u | CGM: 142 (CGM)", note?.notes)
    }

    @Test
    fun bolusUploadTreatments_includesBolusAndContextNote() {
        val uploads = TreatmentMapper.bolusUploadTreatments(
            BolusEntry(
                timestamp = Instant.parse("2026-03-29T10:00:00.000Z"),
                units = 2.0,
                insulinOnBoard = 0.8,
                bloodGlucoseInput = 142,
                bloodGlucoseSource = "CGM",
            ),
        )

        assertEquals(2, uploads.size)
        assertEquals("Correction Bolus", uploads[0].eventType)
        assertEquals("Note", uploads[1].eventType)
        assertEquals(
            Instant.parse(uploads[0].createdAt).plusSeconds(1),
            Instant.parse(uploads[1].createdAt),
        )
    }

    @Test
    fun encodeTreatments_includesEnteredBy() {
        val treatment = TreatmentMapper.bolusToTreatment(
            BolusEntry(
                timestamp = Instant.parse("2026-03-29T10:00:00.000Z"),
                units = 1.5,
            ),
        )
        val json = NightscoutJson.encodeTreatments(listOf(treatment))
        assertTrue(json.contains("\"enteredBy\":\"g2gv000001\""))
    }

    @Test
    fun encodeTreatments_placesCreatedAtAfterNotes() {
        val treatment = TreatmentMapper.bolusToTreatment(
            BolusEntry(
                timestamp = Instant.parse("2026-03-29T10:00:00.000Z"),
                units = 1.5,
                carbsInput = 20.0,
            ),
        )
        val json = NightscoutJson.encodeTreatments(listOf(treatment))
        assertTrue(json.indexOf("\"notes\"") < json.indexOf("\"created_at\""))
    }

    @Test
    fun postTreatments_sendsApiSecretHeader() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        server.start()

        val client = NightscoutClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiSecret = "secret",
        )

        val treatment = TreatmentMapper.bolusToTreatment(
            BolusEntry(
                timestamp = Instant.parse("2026-03-29T10:00:00.000Z"),
                units = 1.5,
                carbsInput = 20.0,
            ),
        )

        val result = client.postTreatments(listOf(treatment))
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.uploadedCount)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/treatments", request.path)
        assertEquals(NightscoutClient.sha1("secret"), request.getHeader("api-secret"))
        assertTrue(request.body.readUtf8().contains("Correction Bolus"))

        val report = result.getOrThrow()
        assertEquals(1, report.batches.size)
        assertEquals(200, report.batches.first().httpCode)
        assertEquals("[]", report.batches.first().responseBody)
        server.shutdown()
    }

    @Test
    fun applyInsulinTimestampJitter_shiftsCreatedAt() {
        val original = TreatmentMapper.bolusToTreatment(
            BolusEntry(
                timestamp = Instant.parse("2026-03-29T10:00:00.000Z"),
                units = 1.5,
            ),
        )
        val jittered = TreatmentMapper.applyInsulinTimestampJitter(original, jitterMicros = 25)

        assertEquals(Instant.parse("2026-03-29T10:00:00.000025Z"), Instant.parse(jittered.createdAt))
        assertEquals(original.notes, jittered.notes)
    }

    @Test
    fun applyInsulinTimestampJitter_ignoresPumpNotes() {
        val note = TreatmentMapper.pumpModeToNote(
            PumpStatistics(
                mode = PumpMode.AUTO,
                autoPercentage = 100.0,
                manualPercentage = 0.0,
                limitedPercentage = 0.0,
            ),
            timestamp = Instant.parse("2026-03-29T10:00:00.000Z"),
        )
        val result = TreatmentMapper.applyInsulinTimestampJitter(note, jitterMicros = 10)
        assertEquals(note, result)
    }

    @Test
    fun treatmentMapper_buildsPumpModeNote() {
        val note = TreatmentMapper.pumpModeToNote(
            PumpStatistics(
                mode = PumpMode.AUTO,
                autoPercentage = 85.0,
                manualPercentage = 10.0,
                limitedPercentage = 5.0,
            ),
        )
        assertEquals("Note", note.eventType)
        assertTrue(note.notes.orEmpty().contains("auto"))
    }
}
