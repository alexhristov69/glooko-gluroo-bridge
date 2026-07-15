package com.glookogluroo.bridge.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val glookoEmail: String = "",
    val glookoPassword: String = "",
    val nightscoutUrl: String = "",
    val nightscoutSecret: String = "",
    val useTokenAuth: Boolean = false,
    val syncEnabled: Boolean = false,
    val backfillDays: Int = 7,
    val syncFromOverride: String = "",
    val postPumpModeNotes: Boolean = true,
    val jitterInsulinTimestamps: Boolean = false,
    val syncIntervalMinutes: Int = 15,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getSettings(): AppSettings {
        return AppSettings(
            glookoEmail = prefs.getString(KEY_GLOOKO_EMAIL, "").orEmpty(),
            glookoPassword = prefs.getString(KEY_GLOOKO_PASSWORD, "").orEmpty(),
            nightscoutUrl = prefs.getString(KEY_NIGHTSCOUT_URL, "").orEmpty(),
            nightscoutSecret = prefs.getString(KEY_NIGHTSCOUT_SECRET, "").orEmpty(),
            useTokenAuth = prefs.getBoolean(KEY_USE_TOKEN_AUTH, false),
            syncEnabled = prefs.getBoolean(KEY_SYNC_ENABLED, false),
            backfillDays = prefs.getInt(KEY_BACKFILL_DAYS, 7),
            syncFromOverride = prefs.getString(KEY_SYNC_FROM_OVERRIDE, "").orEmpty(),
            postPumpModeNotes = prefs.getBoolean(KEY_POST_PUMP_MODE_NOTES, true),
            jitterInsulinTimestamps = prefs.getBoolean(KEY_JITTER_INSULIN_TIMESTAMPS, false),
            syncIntervalMinutes = prefs.getInt(KEY_SYNC_INTERVAL_MINUTES, 15),
        )
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_GLOOKO_EMAIL, settings.glookoEmail.trim())
            .putString(KEY_GLOOKO_PASSWORD, settings.glookoPassword)
            .putString(KEY_NIGHTSCOUT_URL, settings.nightscoutUrl.trim().trimEnd('/'))
            .putString(KEY_NIGHTSCOUT_SECRET, settings.nightscoutSecret.trim())
            .putBoolean(KEY_USE_TOKEN_AUTH, settings.useTokenAuth)
            .putBoolean(KEY_SYNC_ENABLED, settings.syncEnabled)
            .putInt(KEY_BACKFILL_DAYS, settings.backfillDays.coerceIn(1, 30))
            .putString(KEY_SYNC_FROM_OVERRIDE, settings.syncFromOverride.trim())
            .putBoolean(KEY_POST_PUMP_MODE_NOTES, settings.postPumpModeNotes)
            .putBoolean(KEY_JITTER_INSULIN_TIMESTAMPS, settings.jitterInsulinTimestamps)
            .putInt(KEY_SYNC_INTERVAL_MINUTES, settings.syncIntervalMinutes.coerceIn(1, 240))
            .apply()
    }

    fun isConfigured(): Boolean {
        val settings = getSettings()
        return settings.glookoEmail.isNotBlank() &&
            settings.glookoPassword.isNotBlank() &&
            settings.nightscoutUrl.isNotBlank() &&
            settings.nightscoutSecret.isNotBlank()
    }

    companion object {
        private const val PREFS_NAME = "glooko_gluroo_secure_prefs"
        private const val KEY_GLOOKO_EMAIL = "glooko_email"
        private const val KEY_GLOOKO_PASSWORD = "glooko_password"
        private const val KEY_NIGHTSCOUT_URL = "nightscout_url"
        private const val KEY_NIGHTSCOUT_SECRET = "nightscout_secret"
        private const val KEY_USE_TOKEN_AUTH = "use_token_auth"
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_BACKFILL_DAYS = "backfill_days"
        private const val KEY_SYNC_FROM_OVERRIDE = "sync_from_override"
        private const val KEY_POST_PUMP_MODE_NOTES = "post_pump_mode_notes"
        private const val KEY_JITTER_INSULIN_TIMESTAMPS = "jitter_insulin_timestamps"
        private const val KEY_SYNC_INTERVAL_MINUTES = "sync_interval_minutes"
    }
}
