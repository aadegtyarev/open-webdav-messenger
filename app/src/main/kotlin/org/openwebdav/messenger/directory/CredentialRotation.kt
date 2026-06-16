package org.openwebdav.messenger.directory

import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.identity.SealedResult
import org.openwebdav.messenger.transport.ConnectionConfig

/**
 * Host credential rotation: seals a [ConnectionConfig] to a member's X25519 box public key under the
 * host's Ed25519 signature, so the member can silently pick up a new WebDAV credential on the next poll
 * cycle without re-scanning an invite QR.
 *
 * On-disk format (sealed-box content):
 *   payload(JSON) ‖ signature(64, Ed25519) ‖ hostSignPublic(32)
 *
 * The host's public key is embedded in the blob so the recipient can verify the signature without
 * needing to know the host's key out-of-band.
 *
 * Two operations, both pure crypto / serialization — no HTTP, no SQL:
 * - [sealForMember] — serialize the config to JSON, Ed25519-sign with the host's key, append the host's
 *   public key, then [IdentityCrypto.seal] with the member's box public key.
 * - [openForMember] — [IdentityCrypto.openSealed] with the member's box keypair, extract embedded host
 *   public key, verify signature, parse [ConnectionConfig]. Returns `null` on any failure.
 */
internal object CredentialRotation {
    private const val SIGNATURE_BYTES = 64
    private const val PUBLIC_KEY_BYTES = 32

    /**
     * Serialize [config] to JSON, sign with the host's Ed25519 [hostIdentity] signing key, then seal the
     * `signedPayload ‖ signature` under [memberBoxPublicKey] so only the member's box keypair opens it.
     */
    fun sealForMember(
        config: ConnectionConfig,
        memberBoxPublicKey: ByteArray,
        identityCrypto: IdentityCrypto,
        hostIdentity: Identity,
    ): ByteArray {
        val payload = configToJsonBytes(config)
        val signSecret = hostIdentity.copySignSecret()
        val hostSignPub = hostIdentity.copySignPublic()
        val signature: ByteArray
        try {
            signature = identityCrypto.sign(payload, signSecret)
        } finally {
            signSecret.fill(0)
        }
        // Format: payload ‖ signature(64) ‖ hostSignPublic(32)
        val signedBytes = ByteArray(payload.size + SIGNATURE_BYTES + PUBLIC_KEY_BYTES)
        payload.copyInto(signedBytes, 0)
        signature.copyInto(signedBytes, payload.size)
        hostSignPub.copyInto(signedBytes, payload.size + SIGNATURE_BYTES)
        return identityCrypto.seal(signedBytes, memberBoxPublicKey)
    }

    /**
     * Open [blob] with the member's box keypair, extract the embedded host public key, verify the
     * host's Ed25519 signature, then parse the [ConnectionConfig]. Returns `null` on any failure.
     */
    fun openForMember(
        blob: ByteArray,
        identity: Identity,
        identityCrypto: IdentityCrypto,
    ): ConnectionConfig? {
        val boxPub = identity.copyBoxPublic()
        val boxSec = identity.copyBoxSecret()
        val signedBytes: ByteArray
        try {
            when (val opened = identityCrypto.openSealed(blob, boxPub, boxSec)) {
                is SealedResult.Opened -> signedBytes = opened.bytes
                SealedResult.Rejected -> return null
            }
        } finally {
            boxSec.fill(0)
            boxPub.fill(0)
        }
        try {
            if (signedBytes.size < SIGNATURE_BYTES + PUBLIC_KEY_BYTES + 1) return null
            val payloadSize = signedBytes.size - SIGNATURE_BYTES - PUBLIC_KEY_BYTES
            val payload = signedBytes.copyOfRange(0, payloadSize)
            val signature = signedBytes.copyOfRange(payloadSize, payloadSize + SIGNATURE_BYTES)
            val hostSignPublic = signedBytes.copyOfRange(payloadSize + SIGNATURE_BYTES, signedBytes.size)
            if (!identityCrypto.verify(signature, payload, hostSignPublic)) return null
            return jsonToConfig(payload)
        } finally {
            signedBytes.fill(0)
        }
    }

    /** Serialize [ConnectionConfig] to JSON bytes (flat object, no whitespace). */
    private fun configToJsonBytes(config: ConnectionConfig): ByteArray {
        val json =
            buildString {
                append('{')
                append("\"baseUrl\":")
                appendJsonString(this, config.baseUrl)
                append(",\"username\":")
                appendJsonString(this, config.username)
                append(",\"appPassword\":")
                appendJsonString(this, config.appPassword)
                append(",\"chatRoot\":")
                appendJsonString(this, config.chatRoot)
                append('}')
            }
        return json.toByteArray(Charsets.UTF_8)
    }

    /** Parse flat JSON object into [ConnectionConfig], or `null` on malformed input. */
    private fun jsonToConfig(bytes: ByteArray): ConnectionConfig? {
        val text =
            try {
                bytes.toString(Charsets.UTF_8)
            } catch (_: Exception) {
                return null
            }
        val map = parseFlatJson(text) ?: return null
        val baseUrl = map["baseUrl"] ?: return null
        val username = map["username"] ?: return null
        val appPassword = map["appPassword"] ?: return null
        val chatRoot = map["chatRoot"] ?: return null
        return ConnectionConfig(
            baseUrl = baseUrl,
            username = username,
            appPassword = appPassword,
            chatRoot = chatRoot,
        )
    }

    private fun appendJsonString(
        sb: StringBuilder,
        s: String,
    ) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (c < ' ') {
                        sb.append("\\u")
                            .append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
            }
        }
        sb.append('"')
    }

    /** Parse a flat JSON object of string→string pairs, or `null`. Reject-don't-guess. */
    private fun parseFlatJson(text: String): Map<String, String>? {
        var i = 0

        fun skipWs() {
            while (i < text.length && text[i].isWhitespace()) i++
        }

        fun expect(c: Char): Boolean {
            if (i >= text.length || text[i] != c) return false
            i++
            return true
        }

        fun parseString(): String? {
            if (!expect('"')) return null
            val sb = StringBuilder()
            while (true) {
                if (i >= text.length) return null
                val c = text[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= text.length) return null
                        when (text[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > text.length) return null
                                val hex = text.substring(i, i + 4)
                                i += 4
                                val ch = hex.toIntOrNull(16)?.toChar() ?: return null
                                sb.append(ch)
                            }
                            else -> return null
                        }
                    }
                    c < ' ' -> return null
                    else -> sb.append(c)
                }
            }
        }

        skipWs()
        if (!expect('{')) return null
        val out = LinkedHashMap<String, String>()
        skipWs()
        if (i < text.length && text[i] == '}') {
            i++
            return out
        }
        while (true) {
            skipWs()
            val key = parseString() ?: return null
            skipWs()
            if (!expect(':')) return null
            skipWs()
            val value = parseString() ?: return null
            if (out.put(key, value) != null) return null // duplicate key
            skipWs()
            when {
                i >= text.length -> return null
                text[i] == ',' -> i++
                text[i] == '}' -> {
                    i++
                    return out
                }
                else -> return null
            }
        }
    }
}
