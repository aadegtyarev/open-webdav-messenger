package org.openwebdav.messenger.ui.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal object UserSettings {
    private const val PREFS = "owdm_user"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_FONT_SCALE = "font_scale"

    private lateinit var prefs: SharedPreferences
    private val _fontScale = MutableStateFlow(1.0f)
    val fontScaleFlow: StateFlow<Float> = _fontScale

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
}
