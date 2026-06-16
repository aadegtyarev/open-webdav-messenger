package org.openwebdav.messenger.ui.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal object UserSettings {
    private const val PREFS = "owdm_user"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_FONT_SCALE = "font_scale"
    private const val KEY_POLL_INTERVAL_MINUTES = "poll_interval_minutes"
    private const val KEY_COMMUNITY_MIN_POLL_MINUTES = "community_min_poll_minutes"

    private lateinit var prefs: SharedPreferences
    private val _fontScale = MutableStateFlow(1.0f)
    val fontScaleFlow: StateFlow<Float> = _fontScale

    /** Default poll interval: 15 minutes (platform floor). */
    const val DEFAULT_POLL_INTERVAL_MINUTES = 15

    /** Maximum user-selectable poll interval: 60 minutes. */
    const val MAX_POLL_INTERVAL_MINUTES = 60

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _fontScale.value = prefs.getFloat(KEY_FONT_SCALE, 1.0f).coerceIn(0.8f, 1.5f)
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

    /** The member's preferred poll interval in minutes. Defaults to [DEFAULT_POLL_INTERVAL_MINUTES]. */
    var pollIntervalMinutes: Int
        get() =
            prefs.getInt(KEY_POLL_INTERVAL_MINUTES, DEFAULT_POLL_INTERVAL_MINUTES)
                .coerceIn(communityMinPollMinutes, MAX_POLL_INTERVAL_MINUTES)
        set(value) {
            val clamped = value.coerceIn(communityMinPollMinutes, MAX_POLL_INTERVAL_MINUTES)
            prefs.edit().putInt(KEY_POLL_INTERVAL_MINUTES, clamped).apply()
        }

    /**
     * The community-governed minimum poll interval read from `meta/community.json`.
     * Updated by the poll cycle and used as the lower bound for the user's slider.
     * Defaults to [DEFAULT_POLL_INTERVAL_MINUTES] until the first read.
     */
    var communityMinPollMinutes: Int
        get() =
            prefs.getInt(KEY_COMMUNITY_MIN_POLL_MINUTES, DEFAULT_POLL_INTERVAL_MINUTES)
                .coerceIn(1, 1440)
        set(value) {
            val clamped = value.coerceIn(1, 1440)
            prefs.edit().putInt(KEY_COMMUNITY_MIN_POLL_MINUTES, clamped).apply()
        }
}
