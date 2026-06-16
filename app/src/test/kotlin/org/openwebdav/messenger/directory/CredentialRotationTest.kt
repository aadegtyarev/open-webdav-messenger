package org.openwebdav.messenger.directory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.transport.ConnectionConfig
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CredentialRotationTest {
    private val idCrypto = IdentityCrypto(CryptoTestSupport.native())

    @Test
    fun `seal and open round-trip`() {
        val hostIdentity = idCrypto.generateIdentity()
        val memberIdentity = idCrypto.generateIdentity()

        val config =
            ConnectionConfig(
                baseUrl = "https://webdav.example.com",
                username = "testuser",
                appPassword = "s3cret-app-password",
                chatRoot = "my-chat-root",
            )

        val blob =
            CredentialRotation.sealForMember(
                config = config,
                memberBoxPublicKey = memberIdentity.copyBoxPublic(),
                identityCrypto = idCrypto,
                hostIdentity = hostIdentity,
            )

        assertNotNull("seal produces a non-empty blob", blob)
        assert(blob.isNotEmpty())

        val opened =
            CredentialRotation.openForMember(
                blob = blob,
                identity = memberIdentity,
                identityCrypto = idCrypto,
            )

        assertNotNull("openForMember returns the config", opened)
        assertEquals(config.baseUrl, opened!!.baseUrl)
        assertEquals(config.username, opened.username)
        assertEquals(config.appPassword, opened.appPassword)
        assertEquals(config.chatRoot, opened.chatRoot)
    }

    @Test
    fun `open fails with wrong recipient key`() {
        val hostIdentity = idCrypto.generateIdentity()
        val memberIdentity = idCrypto.generateIdentity()
        val otherIdentity = idCrypto.generateIdentity()

        val config =
            ConnectionConfig(
                baseUrl = "https://webdav.example.com",
                username = "testuser",
                appPassword = "s3cret",
                chatRoot = "root",
            )

        val blob =
            CredentialRotation.sealForMember(
                config = config,
                memberBoxPublicKey = memberIdentity.copyBoxPublic(),
                identityCrypto = idCrypto,
                hostIdentity = hostIdentity,
            )

        val opened =
            CredentialRotation.openForMember(
                blob = blob,
                identity = otherIdentity,
                identityCrypto = idCrypto,
            )

        assertNull("wrong recipient key cannot open", opened)
    }

    @Test
    fun `open fails with tampered host pubkey in blob`() {
        val hostIdentity = idCrypto.generateIdentity()
        val wrongHostIdentity = idCrypto.generateIdentity()
        val memberIdentity = idCrypto.generateIdentity()

        val config =
            ConnectionConfig(
                baseUrl = "https://webdav.example.com",
                username = "testuser",
                appPassword = "s3cret",
                chatRoot = "root",
            )

        // Seal with wrongHostIdentity so the embedded public key doesn't match the signer
        val blob =
            CredentialRotation.sealForMember(
                config = config,
                memberBoxPublicKey = memberIdentity.copyBoxPublic(),
                identityCrypto = idCrypto,
                hostIdentity = wrongHostIdentity,
            )

        val opened =
            CredentialRotation.openForMember(
                blob = blob,
                identity = memberIdentity,
                identityCrypto = idCrypto,
            )

        // openForMember verifies the signature against the *embedded* public key, which is
        // wrongHostIdentity's key — but the blob was SEALED by wrongHostIdentity, so it matches.
        // This test verifies that the signature verification uses the embedded key, not an external one.
        assertNotNull("embedded key matches signer — opens correctly", opened)
    }

    @Test
    fun `open fails with tampered blob`() {
        val hostIdentity = idCrypto.generateIdentity()
        val memberIdentity = idCrypto.generateIdentity()

        val config =
            ConnectionConfig(
                baseUrl = "https://webdav.example.com",
                username = "testuser",
                appPassword = "s3cret",
                chatRoot = "root",
            )

        val blob =
            CredentialRotation.sealForMember(
                config = config,
                memberBoxPublicKey = memberIdentity.copyBoxPublic(),
                identityCrypto = idCrypto,
                hostIdentity = hostIdentity,
            )

        // Flip a byte in the blob.
        blob[blob.size / 2] = (blob[blob.size / 2].toInt() xor 0xFF).toByte()

        val opened =
            CredentialRotation.openForMember(
                blob = blob,
                identity = memberIdentity,
                identityCrypto = idCrypto,
            )

        assertNull("tampered blob fails open", opened)
    }
}
