package com.glookogluroo.bridge.nightscout

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

    fun testConnection(): Result<Unit> = runCatching {
        val request = authenticatedRequest("$normalizedBaseUrl/api/v1/status.json").get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Nightscout status check failed: HTTP ${response.code}")
            }
        }
    }

    fun postTreatments(treatments: List<NightscoutTreatment>): Result<Int> = runCatching {
        if (treatments.isEmpty()) return@runCatching 0

        var uploaded = 0
        treatments.chunked(BATCH_SIZE).forEach { batch ->
            val body = NightscoutJson.encodeTreatments(batch)
                .toRequestBody("application/json".toMediaType())
            val request = authenticatedRequest("$normalizedBaseUrl/api/v1/treatments")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    error("Nightscout upload failed: HTTP ${response.code} $errorBody")
                }
                uploaded += batch.size
            }
        }
        uploaded
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
