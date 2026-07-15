package com.glookogluroo.bridge.glooko

class GlookoApiError(
    val url: String,
    val statusCode: Int,
    val bodyPreview: String,
) : Exception("Glooko API HTTP $statusCode for $url — ${bodyPreview.ifBlank { "(empty body)" }}")

data class GlookoTestResult(
    val patientId: String,
    val apiBase: String,
    val dashboardOrigin: String,
    val deviceCount: Int,
)
