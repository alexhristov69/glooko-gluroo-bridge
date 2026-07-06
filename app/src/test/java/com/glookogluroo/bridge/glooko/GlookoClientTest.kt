package com.glookogluroo.bridge.glooko

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

class GlookoClientTest {
    @Test
    fun getGraphData_parsesDeliveredBolusSeries() {
        val server = MockWebServer()
        server.start()

        val apiBase = server.url("/api").toString().trimEnd('/')
        val dashboardOrigin = server.url("/").toString().trimEnd('/')

        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "${dashboardOrigin}/regional/users/sign_in"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    <html><head>
                    <meta name="csrf-token" content="test-csrf-token" />
                    </head><body></body></html>
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    <html><body>
                    <script>
                    window.patient = "test-patient-123";
                    var config = { apiUrl: '$apiBase' };
                    </script>
                    </body></html>
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "series": {
                        "deliveredBolus": [
                          {
                            "timestamp": "2026-03-29T10:00:00.000Z",
                            "insulinDelivered": 2.0,
                            "carbsInput": 25.0
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                ),
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        val httpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val fixedUrl = request.url.toString()
                    .replace("%5B", "[")
                    .replace("%5D", "]")
                chain.proceed(
                    if (fixedUrl == request.url.toString()) request
                    else request.newBuilder().url(fixedUrl).build(),
                )
            }
            .build()

        val client = GlookoClient(
            email = "user@example.com",
            password = "password",
            baseUrl = baseUrl,
            httpClient = httpClient,
        )

        val graphData = client.getGraphData(
            startDate = Instant.parse("2026-03-29T00:00:00.000Z"),
            endDate = Instant.parse("2026-03-29T23:59:59.999Z"),
        )

        assertNotNull(graphData)
        val boluses = GlookoParser.parseBolusEntries(graphData!!)
        assertEquals(1, boluses.size)
        assertEquals(2.0, boluses.first().units, 0.001)

        repeat(3) { server.takeRequest() }
        val graphRequest = server.takeRequest()
        assertTrue(graphRequest.path.orEmpty().contains("series[]=deliveredBolus"))
        server.shutdown()
    }
}
