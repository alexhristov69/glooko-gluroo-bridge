package com.glookogluroo.bridge.nightscout

import com.glookogluroo.bridge.glooko.BolusEntry
import com.glookogluroo.bridge.glooko.PumpMode
import com.glookogluroo.bridge.glooko.PumpStatistics
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
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
        assertEquals(1, result.getOrNull())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/treatments", request.path)
        assertEquals(NightscoutClient.sha1("secret"), request.getHeader("api-secret"))
        assertTrue(request.body.readUtf8().contains("Meal Bolus"))
        server.shutdown()
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
