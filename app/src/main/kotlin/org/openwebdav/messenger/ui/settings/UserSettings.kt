package org.openwebdav.messenger.ui.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal object UserSettings {
    private const val PREFS = "owdm_user"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_FONT_SCALE = "font_scale"
    private const val KEY_POLL_INTERVAL_SECONDS = "poll_interval_seconds"
    private const val KEY_COMMUNITY_MIN_POLL_SECONDS = "community_min_poll_seconds"
    private const val KEY_IS_HOST = "is_host"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_COMMUNITY_RETENTION_WINDOW_DAYS = "community_retention_window_days"

    private lateinit var prefs: SharedPreferences
    private val _fontScale = MutableStateFlow(1.0f)
    val fontScaleFlow: StateFlow<Float> = _fontScale

    private val _themeMode = MutableStateFlow("system")
    val themeModeFlow: StateFlow<String> = _themeMode

    /** Default poll interval: 60 seconds. */
    const val DEFAULT_POLL_INTERVAL_SECONDS = 60

    /** Maximum user-selectable poll interval: 3600 seconds (60 minutes). */
    const val MAX_POLL_INTERVAL_SECONDS = 3600

    /** Default retention window: 14 days. */
    const val DEFAULT_RETENTION_WINDOW_DAYS = 14

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _fontScale.value = prefs.getFloat(KEY_FONT_SCALE, 1.0f).coerceIn(0.8f, 1.5f)
        _themeMode.value = prefs.getString(KEY_THEME_MODE, "system") ?: "system"
    }

    var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value).apply()

    var fontScale: Float
        get() {
            val stored = prefs.getFloat(KEY_FONT_SCALE, 1.0f)
            return stored.coerceIn(0.8f, 1.5f)
        }
        set(value) {
            val clamped = value.coerceIn(0.8f, 1.5f)
            _fontScale.value = clamped
            prefs.edit().putFloat(KEY_FONT_SCALE, clamped).apply()
        }

    /** The member's preferred poll interval in seconds. Defaults to [DEFAULT_POLL_INTERVAL_SECONDS]. */
    var pollIntervalSeconds: Int
        get() =
            prefs.getInt(KEY_POLL_INTERVAL_SECONDS, DEFAULT_POLL_INTERVAL_SECONDS)
                .coerceIn(communityMinPollSeconds, MAX_POLL_INTERVAL_SECONDS)
        set(value) {
            val clamped = value.coerceIn(communityMinPollSeconds, MAX_POLL_INTERVAL_SECONDS)
            prefs.edit().putInt(KEY_POLL_INTERVAL_SECONDS, clamped).apply()
        }

    /**
     * The community-governed minimum poll interval read from `meta/community.json`.
     * Updated by the poll cycle and used as the lower bound for the user's slider.
     * Defaults to [DEFAULT_POLL_INTERVAL_SECONDS] until the first read.
     * Setting also auto-adjusts [pollIntervalSeconds] up to the floor if it is below it
     * (handles the case where a new member joins — their interval becomes the community floor).
     */
    var communityMinPollSeconds: Int
        get() =
            prefs.getInt(KEY_COMMUNITY_MIN_POLL_SECONDS, DEFAULT_POLL_INTERVAL_SECONDS)
                .coerceIn(1, 3600)
        set(value) {
            val clamped = value.coerceIn(1, 3600)
            prefs.edit().putInt(KEY_COMMUNITY_MIN_POLL_SECONDS, clamped).apply()
            // Auto-adjust the member's personal interval up to the new floor.
            if (pollIntervalSeconds < clamped) {
                pollIntervalSeconds = clamped
            }
        }

    /**
     * The user's preferred theme mode: "system", "light", or "dark".
     * Defaults to "system" (follows the OS setting).
     */
    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "system") ?: "system"
        set(value) {
            val valid =
                when (value) {
                    "system", "light", "dark" -> value
                    else -> "system"
                }
            _themeMode.value = valid
            prefs.edit().putString(KEY_THEME_MODE, valid).apply()
        }

    /**
     * The community-governed retention window in days, read from `meta/community.json`
     * and cached during poll cycles. Defaults to [DEFAULT_RETENTION_WINDOW_DAYS].
     */
    var communityRetentionWindowDays: Int
        get() =
            prefs.getInt(KEY_COMMUNITY_RETENTION_WINDOW_DAYS, DEFAULT_RETENTION_WINDOW_DAYS)
                .coerceIn(7, 90)
        set(value) {
            val clamped = value.coerceIn(7, 90)
            prefs.edit().putInt(KEY_COMMUNITY_RETENTION_WINDOW_DAYS, clamped).apply()
        }

    /**
     * Whether the current user is the host of the active community.
     * Persisted across app restarts; set during onboarding.
     */
    var isHost: Boolean
        get() = prefs.getBoolean(KEY_IS_HOST, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_HOST, value).apply()

    /**
     * Convert a poll interval in seconds to a human-readable string.
     * 15→"15s", 60→"1m", 120→"2m", 300→"5m", etc.
     */
    fun formatPollInterval(seconds: Int): String =
        when {
            seconds < 60 -> "${seconds}s"
            seconds % 60 == 0 -> "${seconds / 60}m"
            else -> "${seconds}s"
        }
}
