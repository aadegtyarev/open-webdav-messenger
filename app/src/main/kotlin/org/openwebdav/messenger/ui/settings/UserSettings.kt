package org.openwebdav.messenger.ui.settings

import android.content.Context
import android.content.SharedPreferences

internal object UserSettings {
    private const val PREFS = "owdm_user"
    private const val KEY_DISPLAY_NAME = "display_name"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value).apply()
}
