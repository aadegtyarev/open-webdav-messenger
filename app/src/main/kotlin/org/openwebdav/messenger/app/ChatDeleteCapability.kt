package org.openwebdav.messenger.app

/** Verified remote deletion authority projected for the active exact chat context. */
internal sealed interface ChatDeleteCapability {
    data object Loading : ChatDeleteCapability

    data object Available : ChatDeleteCapability

    data object Unavailable : ChatDeleteCapability

    data object NotAuthorized : ChatDeleteCapability
}
