package com.glookogluroo.bridge.glooko

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

class GlookoClient(
    private val email: String,
    private val password: String,
    private val sessionTimeoutMinutes: Long = 55,
    private val baseUrl: String = GLOOKO_BASE_URL,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    private var patientId: String? = null
    private var apiBase: String? = null
    private var dashboardOrigin: String? = null
    private var authMethod: String? = null
    private var lastAuthTime: Instant? = null

    init {
        authenticate()
    }

    fun ensureAuthenticated() {
        if (lastAuthTime == null) {
            authenticate()
            return
        }
        val elapsedMinutes = (Instant.now().epochSecond - lastAuthTime!!.epochSecond) / 60
        if (elapsedMinutes >= sessionTimeoutMinutes) {
            authenticate()
        }
    }

    fun authenticate() {
        patientId = null
        apiBase = null
        dashboardOrigin = null
        authMethod = null

        val regional = discoverRegionalHosts()
        dashboardOrigin = regional.dashboardOrigin
        apiBase = regional.apiBase

        val jsonLoginError = tryJsonApiLogin(regional)
        if (patientId != null) {
            authMethod = "json-api"
            lastAuthTime = Instant.now()
            return
        }

        tryWebFormLogin(regional, jsonLoginError)
        authMethod = "web-form"
        lastAuthTime = Instant.now()
    }

    private fun discoverRegionalHosts(): RegionalHosts {
        val loginUrl = "$baseUrl$GLOOKO_LOGIN_PATH?id=login_form&locale=en-GB"
        val initialRequest = Request.Builder().url(loginUrl).get().build()
        val initialResponse = httpClient.newCall(initialRequest).execute()
        initialResponse.close()

        var regionalLoginUrl = initialResponse.header("Location") ?: loginUrl
        if (!regionalLoginUrl.startsWith("http")) {
            regionalLoginUrl = resolveUrl(baseUrl, regionalLoginUrl)
        }

        val regionalHttpUrl = regionalLoginUrl.toHttpUrlOrNull()
            ?: throw GlookoAuthError("Could not resolve regional login URL: $regionalLoginUrl")
        val dashboardOrigin = webOrigin(regionalHttpUrl)
        val apiBase = deriveApiBase(regionalHttpUrl)

        return RegionalHosts(
            regionalLoginUrl = regionalLoginUrl,
            dashboardOrigin = dashboardOrigin,
            apiBase = apiBase,
        )
    }

    private fun tryJsonApiLogin(regional: RegionalHosts): String? {
        val body = buildJsonObject {
            putJsonObject("userLogin") {
                put("email", email)
                put("password", password)
            }
            putJsonObject("deviceInformation") {
                put("applicationType", "logbook")
                put("os", "android")
                put("osVersion", "14")
                put("device", "Android")
                put("deviceManufacturer", "Google")
                put("deviceModel", "Mobile")
                put("serialNumber", DEVICE_ID)
                put("clinicalResearch", false)
                put("deviceId", DEVICE_ID)
                put("applicationVersion", "6.1.3")
                put("buildNumber", "0")
                put("gitHash", "g4fbed2011b")
            }
        }.toString()

        val url = "${regional.apiBase}/api/v2/users/sign_in"
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Origin", regional.dashboardOrigin)
            .header("Referer", "${regional.dashboardOrigin}/")
            .build()

        val response = httpClient.newCall(request).execute()
        response.use {
            val responseBody = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                return "HTTP ${it.code}: ${responseBody.take(200)}"
            }
            val glookoCode = extractGlookoCodeFromLoginResponse(responseBody)
            if (glookoCode.isNullOrBlank()) {
                return "HTTP ${it.code} but no glookoCode in response: ${responseBody.take(200)}"
            }
            patientId = glookoCode
            return null
        }
    }

    private fun tryWebFormLogin(regional: RegionalHosts, jsonLoginError: String?) {
        val regionalRequest = Request.Builder().url(regional.regionalLoginUrl).get().build()
        val regionalResponse = httpClient.newCall(regionalRequest).execute()
        regionalResponse.use { response ->
            if (!response.isSuccessful) {
                throw GlookoAuthError("Failed to load regional login page: HTTP ${response.code}")
            }
            val html = response.body?.string().orEmpty()
            val csrfToken = CSRF_TOKEN_REGEX.find(html)?.groupValues?.get(1)
                ?: throw GlookoAuthError("Could not extract CSRF token from login page")

            val loginBody = FormBody.Builder()
                .add("utf8", "\u2713")
                .add("authenticity_token", csrfToken)
                .add("user[email]", email)
                .add("user[password]", password)
                .add("commit", "Log In")
                .build()

            val authRequest = Request.Builder()
                .url(regional.regionalLoginUrl)
                .post(loginBody)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", regional.regionalLoginUrl)
                .build()

            val authResponse = httpClient.newCall(authRequest).execute()
            var (dashboardHtml, finalUrl) = followRedirectsToDashboard(authResponse)

            var extractedPatientId = extractPatientId(dashboardHtml)
            if (extractedPatientId == null && !finalUrl.contains("users/sign_in")) {
                val dashboardFetch = fetchUrl("${regional.dashboardOrigin}/")
                dashboardHtml = dashboardFetch.first
                finalUrl = dashboardFetch.second
                extractedPatientId = extractPatientId(dashboardHtml)
            }

            patientId = extractedPatientId
            if (patientId == null) {
                throw GlookoAuthError(
                    buildAuthFailureMessage(
                        dashboardHtml = dashboardHtml,
                        finalUrl = finalUrl,
                        jsonLoginError = jsonLoginError,
                    ),
                )
            }

            apiBase = extractApiBase(dashboardHtml, finalUrl, regional.apiBase)
            dashboardOrigin = regional.dashboardOrigin
        }
    }

    private fun buildAuthFailureMessage(
        dashboardHtml: String,
        finalUrl: String,
        jsonLoginError: String?,
    ): String = buildString {
        append("Could not complete Glooko sign-in")
        if (dashboardHtml.contains("users/sign_in")) {
            append(" (redirected back to login — session may not have been accepted)")
        } else {
            append(" (signed in but patient ID was not found in dashboard)")
        }
        jsonLoginError?.let { append("\nAPI login: $it") }
        append("\nFinal URL: $finalUrl")
        append("\nTry opening the Glooko app once, then retry.")
    }

    private fun followRedirectsToDashboard(initialResponse: okhttp3.Response): Pair<String, String> {
        var response = initialResponse
        var hops = 0
        try {
            while (response.code in REDIRECT_CODES && hops < MAX_REDIRECTS) {
                val location = response.header("Location")
                    ?: throw GlookoAuthError("Login redirect missing Location header")
                val referer = response.request.url.toString()
                val nextUrl = resolveUrl(referer, location)
                response.close()
                val nextRequest = Request.Builder()
                    .url(nextUrl)
                    .get()
                    .header("Referer", referer)
                    .build()
                response = httpClient.newCall(nextRequest).execute()
                hops++
            }

            if (!response.isSuccessful) {
                throw GlookoAuthError("Login failed: HTTP ${response.code}")
            }

            val html = response.body?.string().orEmpty()
            val finalUrl = response.request.url.toString()
            return html to finalUrl
        } finally {
            response.close()
        }
    }

    private fun fetchUrl(url: String): Pair<String, String> {
        val request = Request.Builder().url(url).get().build()
        val response = httpClient.newCall(request).execute()
        return response.use {
            if (!it.isSuccessful) {
                throw GlookoAuthError("Failed to load $url: HTTP ${it.code}")
            }
            it.body?.string().orEmpty() to it.request.url.toString()
        }
    }

    private fun resolveUrl(currentUrl: String, location: String): String {
        if (location.startsWith("http")) return location
        val base = currentUrl.toHttpUrlOrNull()
            ?: throw GlookoAuthError("Could not resolve redirect URL")
        return base.resolve(location)?.toString()
            ?: throw GlookoAuthError("Could not resolve redirect URL: $location")
    }

    private fun extractPatientId(html: String): String? {
        PATIENT_ID_PATTERNS.forEach { pattern ->
            pattern.find(html)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    private fun extractGlookoCodeFromLoginResponse(responseBody: String): String? {
        val root = runCatching { GlookoParser.parseJsonObject(responseBody) }.getOrNull() ?: return null
        return jsonStringAt(root, "userLogin", "glookoCode")
            ?: jsonStringAt(root, "user", "userLogin", "glookoCode")
            ?: jsonStringAt(root, "glookoCode")
    }

    private fun jsonStringAt(root: JsonObject, vararg path: String): String? {
        var current: kotlinx.serialization.json.JsonElement = root
        for (key in path) {
            val next = (current as? JsonObject)?.get(key) ?: return null
            current = next
        }
        return runCatching { current.jsonPrimitive.content }.getOrNull()
    }

    private fun extractApiBase(html: String, finalUrl: String, fallbackApiBase: String): String {
        API_URL_REGEX.find(html)?.groupValues?.get(1)?.let { return it }
        val httpUrl = finalUrl.toHttpUrlOrNull()
        if (httpUrl != null) {
            return deriveApiBase(httpUrl)
        }
        return fallbackApiBase
    }

    private fun deriveApiBase(webUrl: okhttp3.HttpUrl): String {
        val apiHost = if (webUrl.host.contains("my.glooko")) {
            webUrl.host.replace("my.glooko", "api.glooko")
        } else {
            webUrl.host
        }
        return webOrigin(webUrl.newBuilder().host(apiHost).build())
    }

    private fun webOrigin(url: okhttp3.HttpUrl): String {
        return url.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
    }

    fun getGraphData(startDate: Instant, endDate: Instant): JsonObject? {
        ensureAuthenticated()
        val start = formatIso(startDate)
        val end = formatIso(endDate)
        val query = buildString {
            append("patient=$patientId")
            append("&startDate=$start")
            append("&endDate=$end")
            append("&locale=en-GB")
            append("&insulinTooltips=true")
            append("&filterBgReadings=true")
            append("&splitByDay=false")
            append("&series[]=deliveredBolus")
        }
        val url = "$apiBase/api/v3/graph/data?$query"
        return apiRequest(url, preserveUrl = true)
    }

    fun getStatistics(startDate: Instant, endDate: Instant): JsonObject? {
        ensureAuthenticated()
        val start = formatIso(startDate)
        val end = formatIso(endDate)
        val query = buildString {
            append("patient=$patientId")
            append("&startDate=$start")
            append("&endDate=$end")
            append("&egv=false")
            append("&includeInsulin=true")
            append("&includeExercise=true")
            append("&dow=monday,tuesday,wednesday,thursday,friday,saturday,sunday")
            append("&includePumpModes=true")
        }
        val url = "$apiBase/api/v3/graph/statistics/overall?$query"
        return apiRequest(url)
    }

    fun getDeviceSettings(): JsonObject? {
        ensureAuthenticated()
        val url = "$apiBase/api/v3/devices_and_settings?patient=$patientId"
        return apiRequest(url)
    }

    fun testConnection(): Result<GlookoTestResult> = runCatching {
        ensureAuthenticated()
        val url = "$apiBase/api/v3/devices_and_settings?patient=$patientId"
        val response = executeRequest(url)
        if (!response.isSuccessful) {
            throw GlookoApiError(url, response.code, response.bodyPreview)
        }
        if (response.body.isBlank()) {
            throw GlookoApiError(url, response.code, "(empty JSON body)")
        }
        val deviceData = GlookoParser.parseJsonObject(response.body)
        val devices = GlookoParser.parseDevices(deviceData)
        GlookoTestResult(
            patientId = patientId ?: "unknown",
            apiBase = apiBase ?: "unknown",
            dashboardOrigin = dashboardOrigin ?: "unknown",
            deviceCount = devices.size,
        )
    }

    fun sessionSummary(): String? {
        if (patientId == null) return null
        return buildString {
            append("patient=$patientId")
            append("\napi=$apiBase")
            append("\norigin=$dashboardOrigin")
            authMethod?.let { append("\nauth=$it") }
        }
    }

    fun reconnect(): Boolean = runCatching {
        authenticate()
        true
    }.getOrDefault(false)

    private data class RegionalHosts(
        val regionalLoginUrl: String,
        val dashboardOrigin: String,
        val apiBase: String,
    )

    private data class HttpResult(
        val code: Int,
        val body: String,
        val url: String,
    ) {
        val isSuccessful: Boolean get() = code in 200..299
        val bodyPreview: String get() = body.take(300)
    }

    private fun executeRequest(url: String, preserveUrl: Boolean = false): HttpResult {
        val request = buildApiRequest(url, preserveUrl)
        var response = httpClient.newCall(request).execute()
        if (response.code == 401) {
            response.close()
            authenticate()
            response = httpClient.newCall(request).execute()
        }
        return response.use {
            HttpResult(
                code = it.code,
                body = it.body?.string().orEmpty(),
                url = it.request.url.toString(),
            )
        }
    }

    private fun buildApiRequest(url: String, preserveUrl: Boolean): Request {
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", dashboardOrigin.orEmpty())
            .header("Referer", "${dashboardOrigin.orEmpty()}/")

        return if (preserveUrl) {
            requestBuilder.build().newBuilder().url(url).build()
        } else {
            requestBuilder.build()
        }
    }

    private fun apiRequest(url: String, preserveUrl: Boolean = false): JsonObject? {
        val result = executeRequest(url, preserveUrl)
        if (!result.isSuccessful) return null
        if (result.body.isBlank()) return null
        return runCatching { GlookoParser.parseJsonObject(result.body) }.getOrNull()
    }

    private fun formatIso(instant: Instant): String {
        return ISO_FORMATTER.format(instant.atOffset(ZoneOffset.UTC))
    }

    companion object {
        private const val GLOOKO_BASE_URL = "https://my.glooko.com"
        private const val GLOOKO_LOGIN_PATH = "/users/sign_in"
        private const val MAX_REDIRECTS = 10
        private val REDIRECT_CODES = 300..399
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val DEVICE_ID = UUID.randomUUID().toString().replace("-", "").take(16)
        private val CSRF_TOKEN_REGEX = Regex("""name="csrf-token" content="([^"]+)"""")
        private val PATIENT_ID_PATTERNS = listOf(
            Regex("""window\.patient\s*=\s*"([^"]+)""""),
            Regex("""window\.patient\s*=\s*'([^']+)'"""),
            Regex("""window\.patientId\s*=\s*"([^"]+)""""),
            Regex("""window\.patientId\s*=\s*'([^']+)'"""),
            Regex(""""patient_glooko_code"\s*:\s*"([^"]+)""""),
            Regex(""""glooko_code"\s*:\s*"([^"]+)""""),
        )
        private val API_URL_REGEX = Regex("""apiUrl:\s*'([^']+)'""")
        private val ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

        fun defaultHttpClient(): OkHttpClient {
            val cookieJar = DomainMatchingCookieJar()

            return OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .followRedirects(false)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                        )
                        .build()
                    chain.proceed(request)
                }
                .addInterceptor { chain ->
                    val original = chain.request()
                    val fixedUrl = original.url.toString()
                        .replace("%5B", "[")
                        .replace("%5D", "]")
                    if (fixedUrl == original.url.toString()) {
                        chain.proceed(original)
                    } else {
                        chain.proceed(original.newBuilder().url(fixedUrl).build())
                    }
                }
                .build()
        }
    }
}

private class DomainMatchingCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    override fun saveFromResponse(url: okhttp3.HttpUrl, newCookies: List<Cookie>) {
        if (newCookies.isEmpty()) return
        synchronized(cookies) {
            newCookies.forEach { newCookie ->
                cookies.removeAll {
                    it.name == newCookie.name &&
                        it.domain == newCookie.domain &&
                        it.path == newCookie.path
                }
                cookies.add(newCookie)
            }
        }
    }

    override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> {
        synchronized(cookies) {
            return cookies.filter { it.matches(url) }
        }
    }
}
