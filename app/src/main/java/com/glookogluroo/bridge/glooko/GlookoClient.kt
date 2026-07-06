package com.glookogluroo.bridge.glooko

import kotlinx.serialization.json.JsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
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
        val loginUrl = "$baseUrl$GLOOKO_LOGIN_PATH?id=login_form&locale=en-GB"
        val initialRequest = Request.Builder().url(loginUrl).get().build()
        val initialResponse = httpClient.newCall(initialRequest).execute()
        initialResponse.close()

        var regionalUrl = initialResponse.header("Location") ?: loginUrl
        if (!regionalUrl.startsWith("http")) {
            regionalUrl = "$baseUrl$regionalUrl"
        }

        val regionalRequest = Request.Builder().url(regionalUrl).get().build()
        val regionalResponse = httpClient.newCall(regionalRequest).execute()
        regionalResponse.use { response ->
            if (!response.isSuccessful) {
                throw GlookoAuthError("Failed to load regional login page: HTTP ${response.code}")
            }
            val html = response.body?.string().orEmpty()
            val csrfToken = CSRF_TOKEN_REGEX.find(html)?.groupValues?.get(1)
                ?: throw GlookoAuthError("Could not extract CSRF token from login page")

            val loginBody = FormBody.Builder()
                .add("authenticity_token", csrfToken)
                .add("user[email]", email)
                .add("user[password]", password)
                .add("commit", "Log In")
                .build()

            val authRequest = Request.Builder()
                .url(regionalUrl)
                .post(loginBody)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", regionalUrl)
                .build()

            val authResponse = httpClient.newCall(authRequest).execute()
            authResponse.use { auth ->
                val dashboardHtml = auth.body?.string().orEmpty()
                val finalUrl = auth.request.url.toString()

                patientId = PATIENT_ID_REGEX.find(dashboardHtml)?.groupValues?.get(1)
                    ?: throw GlookoAuthError("Could not extract patient ID — credentials may be invalid")

                apiBase = API_URL_REGEX.find(dashboardHtml)?.groupValues?.get(1)
                    ?: run {
                        val httpUrl = finalUrl.toHttpUrlOrNull()
                            ?: throw GlookoAuthError("Could not determine API base URL")
                        val apiHost = httpUrl.host.replace("my.glooko", "api.glooko")
                        "${httpUrl.scheme}://$apiHost"
                    }

                val dashboardHttpUrl = finalUrl.toHttpUrlOrNull()
                    ?: throw GlookoAuthError("Could not determine dashboard origin")
                dashboardOrigin = "${dashboardHttpUrl.scheme}://${dashboardHttpUrl.host}"
                lastAuthTime = Instant.now()
            }
        }
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

    fun testConnection(): Boolean = runCatching {
        ensureAuthenticated()
        getDeviceSettings() != null
    }.getOrDefault(false)

    fun reconnect(): Boolean = runCatching {
        authenticate()
        true
    }.getOrDefault(false)

    private fun apiRequest(url: String, preserveUrl: Boolean = false): JsonObject? {
        val requestBuilder = Request.Builder()
            .url(if (preserveUrl) url else url)
            .get()
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", dashboardOrigin.orEmpty())
            .header("Referer", "${dashboardOrigin.orEmpty()}/")

        val request = if (preserveUrl) {
            // OkHttp percent-encodes brackets; Glooko requires literal series[].
            requestBuilder.build().newBuilder().url(url).build()
        } else {
            requestBuilder.build()
        }

        var response = httpClient.newCall(request).execute()
        if (response.code == 401) {
            response.close()
            authenticate()
            response = httpClient.newCall(request).execute()
        }

        response.use {
            if (!it.isSuccessful) return null
            val body = it.body?.string().orEmpty()
            if (body.isBlank()) return null
            return GlookoParser.parseJsonObject(body)
        }
    }

    private fun formatIso(instant: Instant): String {
        return ISO_FORMATTER.format(instant.atOffset(ZoneOffset.UTC))
    }

    companion object {
        private const val GLOOKO_BASE_URL = "https://my.glooko.com"
        private const val GLOOKO_LOGIN_PATH = "/users/sign_in"
        private val CSRF_TOKEN_REGEX = Regex("""name="csrf-token" content="([^"]+)"""")
        private val PATIENT_ID_REGEX = Regex("""window\.patient\s*=\s*"([^"]+)"""")
        private val API_URL_REGEX = Regex("""apiUrl:\s*'([^']+)'""")
        private val ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

        fun defaultHttpClient(): OkHttpClient {
            val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()
            val cookieJar = object : CookieJar {
                override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
                    cookieStore.getOrPut(url.host) { mutableListOf() }.apply {
                        clear()
                        addAll(cookies)
                    }
                }

                override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> {
                    return cookieStore[url.host].orEmpty()
                }
            }

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
