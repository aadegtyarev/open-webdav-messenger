package org.openwebdav.messenger.keystore

import android.content.Context

/** Persists the selected community identifier across process restarts. */
internal class ActiveCommunityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(fallback: String): String {
        AccountIdentifier.requireValid(fallback)
        return preferences.getString(KEY, null)?.takeIf(AccountIdentifier::isValid) ?: fallback
    }

    fun select(communityId: String) {
        AccountIdentifier.requireValid(communityId)
        preferences.edit().putString(KEY, communityId).apply()
    }

    fun selectStrict(communityId: String) {
        AccountIdentifier.requireValid(communityId)
        check(preferences.edit().putString(KEY, communityId).commit()) { "Failed to persist active community" }
    }

    fun clear() {
        check(preferences.edit().remove(KEY).commit()) { "Failed to clear active community" }
    }

    private companion object {
        const val PREFS = "owdm.active-community"
        const val KEY = "community_id"
    }
}
