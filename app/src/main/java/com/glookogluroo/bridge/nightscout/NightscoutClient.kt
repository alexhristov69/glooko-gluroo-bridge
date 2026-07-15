package com.glookogluroo.bridge.nightscout

import com.glookogluroo.bridge.util.JsonFormatter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class NightscoutClient(
    private val baseUrl: String,
    private val apiSecret: String,
    private val useTokenAuth: Boolean = false,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')
    private val apiSecretHash = sha1(apiSecret)

    fun testConnection(): Result<String> = runCatching {
        val url = "$normalizedBaseUrl/api/v1/status.json"
        val authMode = if (useTokenAuth) "token query param" else "api-secret header (SHA1)"
        val request = authenticatedRequest(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error(
                    "Nightscout status check failed: HTTP ${response.code}\n" +
                        "URL: $url\n" +
                        "Auth: $authMode\n" +
                        "Body: ${body.take(300).ifBlank { "(empty)" }}",
                )
            }
            buildString {
                append("HTTP ${response.code} OK")
                append("\nURL: $url")
                append("\nAuth: $authMode")
                if (body.isNotBlank()) {
                    append("\nBody:")
                    append("\n")
                    append(JsonFormatter.prettify(body.take(2000)))
                }
            }
        }
    }

    fun postTreatments(treatments: List<NightscoutTreatment>): Result<NightscoutUploadReport> = runCatching {
        if (treatments.isEmpty()) {
            return@runCatching NightscoutUploadReport(uploadedCount = 0, batches = emptyList())
        }

        val batches = mutableListOf<NightscoutBatchResponse>()
        var uploaded = 0
        treatments.chunked(BATCH_SIZE).forEachIndexed { index, batch ->
            val requestBody = NightscoutJson.encodeTreatments(batch)
            val url = "$normalizedBaseUrl/api/v1/treatments"
            val httpRequest = authenticatedRequest(url)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error(
                        buildString {
                            append("Nightscout upload failed: HTTP ${response.code}")
                            append("\nURL: $url")
                            append("\nResponse: ${responseBody.ifBlank { "(empty)" }}")
                        },
                    )
                }
                batches += NightscoutBatchResponse(
                    batchIndex = index,
                    batchSize = batch.size,
                    httpCode = response.code,
                    requestUrl = url,
                    requestBody = requestBody,
                    responseBody = responseBody,
                )
                uploaded += batch.size
            }
        }
        NightscoutUploadReport(uploadedCount = uploaded, batches = batches)
    }

    private fun authenticatedRequest(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (useTokenAuth) {
            val separator = if (url.contains("?")) "&" else "?"
            builder.url("$url${separator}token=$apiSecret")
        } else {
            builder.header("api-secret", apiSecretHash)
        }
        return builder
    }

    companion object {
        private const val BATCH_SIZE = 50

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        fun sha1(value: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
            val hash = digest.digest(value.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }
    }
}
