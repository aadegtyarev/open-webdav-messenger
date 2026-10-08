package org.openwebdav.messenger.app

import org.openwebdav.messenger.protocol.Base32
import org.openwebdav.messenger.protocol.Hex

internal object ChatDescriptorIdMatcher {
    fun matches(
        descriptorId: ByteArray,
        chatId: String,
    ): Boolean = Hex.encode(descriptorId) == chatId || Base32.encodeBase32Lower(descriptorId) == chatId
}
