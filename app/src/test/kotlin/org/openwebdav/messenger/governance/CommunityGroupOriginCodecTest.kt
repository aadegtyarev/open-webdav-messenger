package org.openwebdav.messenger.governance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.identity.IdentityTestSupport

class CommunityGroupOriginCodecTest {
    private val crypto = IdentityTestSupport.identityCrypto()

    @Test
    fun origin_signature_binds_community_chat_access_and_creator_keys() {
        val creator = crypto.generateIdentity()
        val origin =
            CommunityGroupOrigin(
                "community-1",
                "abcdefghijklmnopqrstuvwxyz234567",
                ChatAccess.PRIVATE,
                creator.copySignPublic(),
                creator.copyBoxPublic(),
                ByteArray(32) { 9 },
            )
        val bytes = CommunityGroupOriginCodec.sign(origin, creator.copySignSecret(), crypto)
        val verified = CommunityGroupOriginCodec.verify(bytes, origin.communityId, origin.chatId, crypto)

        assertEquals(ChatAccess.PRIVATE, verified?.access)
        assertTrue(verified!!.creatorSigningPublicKey.contentEquals(origin.creatorSigningPublicKey))
        assertNull(CommunityGroupOriginCodec.verify(bytes, "other-community", origin.chatId, crypto))
        assertNull(CommunityGroupOriginCodec.verify(bytes, origin.communityId, "bcdefghijklmnopqrstuvwxyz234567a", crypto))
        val tampered = bytes.copyOf().also { it[bytes.lastIndex - 1] = (it.last().toInt() xor 1).toByte() }
        assertNull(CommunityGroupOriginCodec.verify(tampered, origin.communityId, origin.chatId, crypto))
    }
}
