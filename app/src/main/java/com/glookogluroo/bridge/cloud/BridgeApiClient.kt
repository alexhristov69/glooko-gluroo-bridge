package com.glookogluroo.bridge.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class BridgeApiException(message: String, val statusCode: Int = 0) : Exception(message)

@Singleton
class BridgeApiClient @Inject constructor(
    private val authRepository: CognitoAuthRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun putSettings(body: JsonObject): JsonObject = request("PUT", "/settings", body)

    suspend fun startRun(mode: String): JsonObject = request(
        "POST",
        "/runs",
        buildJsonObject { put("mode", mode) },
    )

    suspend fun getRun(runId: String): JsonObject = request("GET", "/runs/$runId")

    suspend fun getStatus(): JsonObject = request("GET", "/status")

    suspend fun resetSync(): JsonObject = request("POST", "/admin/reset-sync")

    suspend fun clearHistory(): JsonObject = request("POST", "/admin/clear-history")

    suspend fun pollRunUntilDone(
        runId: String,
        pollMs: Long = 2_000,
        maxWaitMs: Long = 180_000,
    ): JsonObject = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val run = getRun(runId)
            val status = run.string("status")
            if (status == "SUCCEEDED" || status == "FAILED") {
                return@withContext run
            }
            delay(pollMs)
        }
        error("Timed out waiting for run $runId")
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JsonObject? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        if (!CloudConfig.enabled) {
            throw BridgeApiException("Cloud API is not configured")
        }
        val session = authRepository.refreshIfNeeded()
            ?: throw BridgeApiException("Not signed in", 401)
        val builder = Request.Builder()
            .url("${CloudConfig.apiBaseUrl}$path")
            .header("Authorization", "Bearer ${session.idToken}")
            .header("Accept", "application/json")
        when (method) {
            "GET" -> builder.get()
            "PUT" -> builder.put((body ?: buildJsonObject {}).toString().toRequestBody(JSON))
            "POST" -> builder.post((body ?: buildJsonObject {}).toString().toRequestBody(JSON))
            else -> error("Unsupported method $method")
        }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching {
                if (text.isBlank()) {
                    buildJsonObject {}
                } else {
                    val element = json.parseToJsonElement(text)
                    element as? JsonObject
                        ?: buildJsonObject { put("raw", text) }
                }
            }.getOrElse { buildJsonObject { put("raw", text) } }
            if (!response.isSuccessful) {
                val message = parsed.string("error")
                    .ifBlank { parsed.string("message") }
                    .ifBlank { "HTTP ${response.code}" }
                throw BridgeApiException(message, response.code)
            }
            parsed
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}

private fun JsonObject.elementOrNull(key: String) =
    this[key]?.takeUnless { it is JsonNull }

fun JsonObject.string(key: String, default: String = ""): String {
    val el = elementOrNull(key) ?: return default
    return (el as? JsonPrimitive)?.contentOrNull ?: default
}

fun JsonObject.stringOrNull(key: String): String? {
    val el = elementOrNull(key) ?: return null
    return (el as? JsonPrimitive)?.contentOrNull
}

fun JsonObject.bool(key: String, default: Boolean = false): Boolean {
    val el = elementOrNull(key) ?: return default
    val prim = el as? JsonPrimitive ?: return default
    return prim.contentOrNull?.toBooleanStrictOrNull()
        ?: when (prim.content) {
            "true", "True", "1" -> true
            "false", "False", "0" -> false
            else -> default
        }
}

fun JsonObject.long(key: String, default: Long = 0L): Long {
    val el = elementOrNull(key) ?: return default
    return (el as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: default
}

fun JsonObject.int(key: String, default: Int = 0): Int {
    val el = elementOrNull(key) ?: return default
    return (el as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: default
}

fun JsonObject.obj(key: String): JsonObject? =
    elementOrNull(key) as? JsonObject
