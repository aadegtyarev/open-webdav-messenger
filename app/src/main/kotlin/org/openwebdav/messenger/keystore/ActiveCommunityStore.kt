package org.openwebdav.messenger.keystore

import android.content.Context

/** Persists the selected community identifier across process restarts. */
internal class ActiveCommunityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(fallback: String): String = preferences.getString(KEY, null) ?: fallback

    fun select(communityId: String) {
        preferences.edit().putString(KEY, communityId).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY).apply()
    }

    private companion object {
        const val PREFS = "owdm.active-community"
        const val KEY = "community_id"
    }
}
