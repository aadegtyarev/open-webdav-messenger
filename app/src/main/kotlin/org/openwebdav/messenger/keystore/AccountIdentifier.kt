package org.openwebdav.messenger.keystore

/** Shared identifier bounds for community and chat stores and account-backup validation. */
internal object AccountIdentifier {
    const val MAX_LENGTH = 96

    private val PATTERN = Regex("[A-Za-z0-9_-]{1,$MAX_LENGTH}")

    fun isValid(value: String): Boolean = PATTERN.matches(value)

    fun requireValid(value: String) {
        require(isValid(value)) { "Invalid account identifier" }
    }
}
