package org.openwebdav.messenger.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPermissionPolicyTest {
    @Test
    fun older_android_does_not_require_runtime_permission() {
        assertEquals(
            NotificationPermissionPolicy.State.NotRequired,
            NotificationPermissionPolicy.state(32, granted = false, requestAlreadyMade = false),
        )
    }

    @Test
    fun first_denial_flow_offers_one_contextual_request_then_settings_recovery() {
        assertEquals(
            NotificationPermissionPolicy.State.RequestAvailable,
            NotificationPermissionPolicy.state(33, granted = false, requestAlreadyMade = false),
        )
        assertEquals(
            NotificationPermissionPolicy.State.SettingsRecovery,
            NotificationPermissionPolicy.state(35, granted = false, requestAlreadyMade = true),
        )
    }

    @Test
    fun permission_grant_takes_precedence_over_request_history() {
        assertEquals(
            NotificationPermissionPolicy.State.Granted,
            NotificationPermissionPolicy.state(35, granted = true, requestAlreadyMade = true),
        )
    }
}
