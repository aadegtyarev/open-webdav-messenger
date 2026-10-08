package org.openwebdav.messenger.app

/** Decision policy for contextual Android notification permission requests and denial recovery. */
internal object NotificationPermissionPolicy {
    enum class State { NotRequired, Granted, RequestAvailable, SettingsRecovery }

    fun state(
        apiLevel: Int,
        granted: Boolean,
        requestAlreadyMade: Boolean,
    ): State =
        when {
            apiLevel < ANDROID_13_API -> State.NotRequired
            granted -> State.Granted
            requestAlreadyMade -> State.SettingsRecovery
            else -> State.RequestAvailable
        }

    const val ANDROID_13_API = 33
}
