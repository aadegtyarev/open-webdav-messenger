package org.openwebdav.messenger.membership

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.directory.DirectoryEntry
import org.openwebdav.messenger.identity.IdentityCrypto

class PrivateMembershipResolverTest {
    private val identity = IdentityCrypto(CryptoTestSupport.native()).generateIdentity()
    private val claim = PrivateMembershipClaim("chat_01", "Self asserted", identity.copySignPublic(), identity.copyBoxPublic())

    @Test
    fun missing_directory_keeps_claim_private_chat_only() {
        val result = PrivateMembershipResolver.resolve(listOf(claim), null)
        assertEquals(1, result.members.size)
        assertEquals(MembershipIdentityProvenance.PRIVATE_CHAT_ONLY, result.members.single().provenance)
    }

    @Test
    fun exact_directory_pair_upgrades_name_and_provenance() {
        val entry = DirectoryEntry("Verified name", identity.copySignPublic(), identity.copyBoxPublic())
        val result = PrivateMembershipResolver.resolve(listOf(claim), listOf(entry))
        assertEquals("Verified name", result.members.single().displayName)
        assertEquals(MembershipIdentityProvenance.COMMUNITY_DIRECTORY, result.members.single().provenance)
    }

    @Test
    fun directory_conflict_and_signer_equivocation_are_rejected() {
        val conflict = DirectoryEntry("Other box", identity.copySignPublic(), ByteArray(32) { 9 })
        assertEquals(0, PrivateMembershipResolver.resolve(listOf(claim), listOf(conflict)).members.size)
        val equivocation = PrivateMembershipClaim("chat_01", "Other", identity.copySignPublic(), ByteArray(32) { 8 })
        assertEquals(0, PrivateMembershipResolver.resolve(listOf(claim, equivocation), null).members.size)
    }
}
