package com.glookogluroo.bridge.cloud

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class AuthSession(
    val idToken: String,
    val accessToken: String,
    val refreshToken: String,
    val email: String,
)

@Singleton
class CognitoAuthRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "g2g_cloud_auth",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getCachedSession(): AuthSession? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        return AuthSession(id, access, refresh, email)
    }

    fun isSignedIn(): Boolean = getCachedSession() != null

    fun signOut() {
        prefs.edit().clear().apply()
    }

    suspend fun signIn(email: String, password: String): Result<AuthSession> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("AuthFlow", "USER_PASSWORD_AUTH")
                put("ClientId", CloudConfig.cognitoClientId)
                putJsonObject("AuthParameters") {
                    put("USERNAME", email.trim())
                    put("PASSWORD", password)
                }
            }
            val resp = cognitoCall("AWSCognitoIdentityProviderService.InitiateAuth", body)
            val result = resp["AuthenticationResult"]?.jsonObject
                ?: error(resp["message"]?.jsonPrimitive?.contentOrNull ?: "Sign-in failed")
            val session = AuthSession(
                idToken = result.getValue("IdToken").jsonPrimitive.content,
                accessToken = result.getValue("AccessToken").jsonPrimitive.content,
                refreshToken = result["RefreshToken"]?.jsonPrimitive?.content
                    ?: prefs.getString(KEY_REFRESH, "") ?: "",
                email = email.trim(),
            )
            save(session)
            session
        }
    }

    suspend fun signUp(email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("ClientId", CloudConfig.cognitoClientId)
                put("Username", email.trim())
                put("Password", password)
                put(
                    "UserAttributes",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("Name", "email")
                                put("Value", email.trim())
                            },
                        )
                    },
                )
            }
            val resp = cognitoCall("AWSCognitoIdentityProviderService.SignUp", body)
            if (resp.containsKey("__type") && resp["__type"]?.jsonPrimitive?.contentOrNull?.contains("Exception") == true) {
                error(resp["message"]?.jsonPrimitive?.contentOrNull ?: "Sign-up failed")
            }
            Unit
        }
    }

    suspend fun confirmSignUp(email: String, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("ClientId", CloudConfig.cognitoClientId)
                put("Username", email.trim())
                put("ConfirmationCode", code.trim())
            }
            cognitoCall("AWSCognitoIdentityProviderService.ConfirmSignUp", body)
            Unit
        }
    }

    suspend fun refreshIfNeeded(): AuthSession? = withContext(Dispatchers.IO) {
        val cached = getCachedSession() ?: return@withContext null
        runCatching {
            val body = buildJsonObject {
                put("AuthFlow", "REFRESH_TOKEN_AUTH")
                put("ClientId", CloudConfig.cognitoClientId)
                putJsonObject("AuthParameters") {
                    put("REFRESH_TOKEN", cached.refreshToken)
                }
            }
            val resp = cognitoCall("AWSCognitoIdentityProviderService.InitiateAuth", body)
            val result = resp["AuthenticationResult"]?.jsonObject ?: return@runCatching cached
            val session = cached.copy(
                idToken = result.getValue("IdToken").jsonPrimitive.content,
                accessToken = result.getValue("AccessToken").jsonPrimitive.content,
            )
            save(session)
            session
        }.getOrDefault(cached)
    }

    private fun save(session: AuthSession) {
        prefs.edit()
            .putString(KEY_ID, session.idToken)
            .putString(KEY_ACCESS, session.accessToken)
            .putString(KEY_REFRESH, session.refreshToken)
            .putString(KEY_EMAIL, session.email)
            .apply()
    }

    private fun cognitoCall(target: String, body: JsonObject): JsonObject {
        if (!CloudConfig.enabled) {
            error("Cloud sync is not configured (set g2g.apiBaseUrl and g2g.cognitoClientId)")
        }
        val request = Request.Builder()
            .url(CloudConfig.cognitoEndpoint)
            .header("Content-Type", "application/x-amz-json-1.1")
            .header("X-Amz-Target", target)
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
                error("Cognito error HTTP ${response.code}: $text")
            }
            if (!response.isSuccessful) {
                error(parsed["message"]?.jsonPrimitive?.contentOrNull ?: "HTTP ${response.code}")
            }
            return parsed
        }
    }

    companion object {
        private val JSON = "application/x-amz-json-1.1".toMediaType()
        private const val KEY_ID = "id_token"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EMAIL = "email"
    }
}
