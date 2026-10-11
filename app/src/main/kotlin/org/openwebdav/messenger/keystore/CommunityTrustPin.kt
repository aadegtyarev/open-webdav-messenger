package org.openwebdav.messenger.keystore

internal enum class TrustPinSource { OBSERVED, OWNER_CREATED, GOVERNANCE_INVITE }

internal enum class TrustPinResult { PINNED, ALREADY_PINNED, CONFLICT, INVALID_GENESIS }
