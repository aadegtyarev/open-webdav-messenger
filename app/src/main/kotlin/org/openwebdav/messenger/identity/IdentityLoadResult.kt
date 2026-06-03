package org.openwebdav.messenger.identity

/**
 * The typed result of [IdentityStore.load] (`docs/architecture.md` decision 10 fix 3). The identity is
 * the user's account: losing it silently means they can no longer decrypt or be verified. So the load
 * path **never** lets a Keystore/GCM exception escape and crash the app, and **never** collapses "the
 * file exists but cannot be decrypted" into "no identity" (which would trigger silent regeneration =
 * silent account loss). Instead it distinguishes three cases:
 */
sealed interface IdentityLoadResult {
    /** No identity file yet — the **only** case in which [IdentityStore.loadOrCreate] may generate. */
    data object None : IdentityLoadResult

    /** Success — the stored [identity]. */
    class Loaded(val identity: Identity) : IdentityLoadResult

    /**
     * The identity file EXISTS but cannot be decrypted/unwrapped — a corrupt/partial blob, or the
     * Keystore wrapping key was invalidated by an OS/lockscreen change (AEADBadTagException /
     * KeyPermanentlyInvalidatedException). [reason] is a non-secret diagnostic. [IdentityStore.loadOrCreate]
     * MUST surface this (it throws [IdentityUnrecoverableException]) and MUST NOT silently regenerate a
     * new identity — the caller (future UI) decides: re-import, reset-with-warning, etc.
     */
    class Unrecoverable(val reason: String, val cause: Throwable? = null) : IdentityLoadResult
}

/**
 * Thrown by [IdentityStore.loadOrCreate] when a stored identity file exists but is unrecoverable. A
 * **typed** failure, not an uncaught crash and not silent regeneration — surfacing account-loss to the
 * caller so it can offer re-import / reset-with-warning (decision 10 fix 3).
 */
class IdentityUnrecoverableException(
    val reason: String,
    cause: Throwable? = null,
) : Exception("stored identity exists but cannot be recovered: $reason", cause)
