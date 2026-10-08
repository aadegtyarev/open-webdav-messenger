package org.openwebdav.messenger.invite

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.identity.IdentityCrypto
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * The `owdm1:` invite-token codec (`ui-chat-surface` plan → Contracts; arch note Choice 2). Frames an
 * [InviteToken] as `owdm1:<base64url(gzip(json))>` for out-of-band transit and decodes it back.
 *
 * **Plain encoding, not encryption** — a bearer token (whoever holds it is in; the on-screen warning and
 * trusted-channel sharing are the only mitigations, per the threat model). The framing is the codec's own;
 * the only thing it borrows from `crypto` is the raw chat-key bytes (carried via `ChatKey.export()` at the
 * call site, imported via `KeySources.importRawKey(raw)` at the join site — both in-memory only).
 *
 * **Reject-don't-guess decode** ([decode]): a wrong prefix (a random QR / noise), bad base64url, bad gzip,
 * or a missing/invalid field is a typed [Result.Rejected] — never a partial or guessed config, never a
 * crash (Scenario 4 / contract `invite_decode_rejects_non_owdm_or_malformed_token`). The decoded token is
 * held in memory only and is never logged (its [InviteToken.toString] is redacted).
 *
 * The gzip + base64url work runs off the UI thread ([ioDispatcher], default [Dispatchers.IO]) — a few-KB
 * payload is cheap, but the codec must not block a composable (stack-notes Kotlin off-main-thread).
 */
internal class InviteCodec(
    private val identityCrypto: IdentityCrypto,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Encode [token] to the `owdm1:<base64url(gzip(json))>` string, off the UI thread. */
    suspend fun encode(token: InviteToken): String =
        withContext(ioDispatcher) {
            require(isAuthenticated(token)) { "invite access metadata is unauthenticated" }
            val json = FlatJson.encode(toFields(token))
            val gzipped = gzip(json.toByteArray(Charsets.UTF_8))
            PREFIX + base64Url.encodeToString(gzipped)
        }

    fun sign(
        token: InviteToken,
        signingSecret: ByteArray,
    ): InviteToken {
        require(token.access == ChatAccess.PUBLIC || token.access == ChatAccess.PRIVATE)
        val unsigned = token.copy(signature = ByteArray(InviteToken.SIGNATURE_BYTES))
        return unsigned.copy(signature = identityCrypto.sign(signatureInput(unsigned), signingSecret))
    }

    /** Decode an `owdm1:` [text] back to an [InviteToken], or a typed [Result.Rejected], off the UI thread. */
    suspend fun decode(text: String): Result =
        withContext(ioDispatcher) {
            decodeBlocking(text)
        }

    /** The pure (non-suspend) decode core — also the unit-test entry point for the reject cases. */
    fun decodeBlocking(text: String): Result {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX)) return Result.Rejected
        val payload = trimmed.removePrefix(PREFIX)
        val gzipped = decodeBase64Url(payload) ?: return Result.Rejected
        val json = gunzip(gzipped) ?: return Result.Rejected
        val fields = FlatJson.decode(json.toString(Charsets.UTF_8)) ?: return Result.Rejected
        if (fields[KEY_VERSION] == LEGACY_FORMAT_VERSION) return Result.Legacy
        val token = fromFields(fields) ?: return Result.Rejected
        if (!isAuthenticated(token)) {
            token.chatKey.fill(0)
            return Result.Rejected
        }
        return Result.Decoded(token)
    }

    private fun toFields(token: InviteToken): Map<String, String> =
        fieldsWithoutAuthentication(token) +
            mapOf(
                KEY_VERSION to FORMAT_VERSION,
                KEY_SIGNER to base64Url.encodeToString(token.signingPublicKey),
                KEY_SIGNATURE to base64Url.encodeToString(token.signature),
            )

    private fun fieldsWithoutAuthentication(token: InviteToken): LinkedHashMap<String, String> =
        linkedMapOf(
            KEY_BASE_URL to token.baseUrl,
            KEY_USERNAME to token.username,
            KEY_APP_PASSWORD to token.appPassword,
            KEY_CHAT_ROOT to token.chatRoot,
            KEY_CHAT_ID to token.chatId,
            KEY_CHAT_KEY to base64Url.encodeToString(token.chatKey),
            KEY_COMMUNITY to token.communityName,
            KEY_ACCESS to accessName(token.access),
        )

    private fun signatureInput(token: InviteToken): ByteArray =
        SIGNATURE_DOMAIN.toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
            FlatJson.encode(fieldsWithoutAuthentication(token)).toByteArray(Charsets.UTF_8)

    private fun isAuthenticated(token: InviteToken): Boolean =
        (token.access == ChatAccess.PUBLIC || token.access == ChatAccess.PRIVATE) &&
            identityCrypto.verify(token.signature, signatureInput(token), token.signingPublicKey)

    private fun fromFields(fields: Map<String, String>): InviteToken? {
        if (fields[KEY_VERSION] != FORMAT_VERSION || fields.keys != CURRENT_FIELDS) return null
        val access = parseAccess(fields[KEY_ACCESS] ?: return null) ?: return null
        val rawKey = decodeBase64Url(fields[KEY_CHAT_KEY] ?: return null) ?: return null
        val signer = decodeBase64Url(fields[KEY_SIGNER] ?: return null) ?: return null
        val signature = decodeBase64Url(fields[KEY_SIGNATURE] ?: return null) ?: return null
        if (rawKey.size != InviteToken.CHAT_KEY_BYTES || signer.size != InviteToken.SIGNING_KEY_BYTES ||
            signature.size != InviteToken.SIGNATURE_BYTES
        ) {
            rawKey.fill(0)
            return null
        }
        return InviteToken(
            baseUrl = fields[KEY_BASE_URL] ?: return null,
            username = fields[KEY_USERNAME] ?: return null,
            appPassword = fields[KEY_APP_PASSWORD] ?: return null,
            chatRoot = fields[KEY_CHAT_ROOT] ?: return null,
            chatId = fields[KEY_CHAT_ID] ?: return null,
            chatKey = rawKey,
            communityName = fields[KEY_COMMUNITY] ?: return null,
            access = access,
            signingPublicKey = signer,
            signature = signature,
        )
    }

    private fun accessName(access: ChatAccess): String =
        when (access) {
            ChatAccess.PUBLIC -> "public"
            ChatAccess.PRIVATE -> "private"
        }

    private fun parseAccess(value: String): ChatAccess? =
        when (value) {
            "public" -> ChatAccess.PUBLIC
            "private" -> ChatAccess.PRIVATE
            else -> null
        }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(bytes) }
        return out.toByteArray()
    }

    /**
     * Inflate [bytes] with a hard output cap ([MAX_INFLATED_BYTES]). The input is attacker-controlled (a
     * scanned/pasted foreign token), so an unbounded inflate is a decompression-bomb DoS — a few-hundred-byte
     * payload can inflate to gigabytes and OOM the process before the reject-don't-guess validation runs.
     * Reading one byte past the cap is a typed rejection (return `null`), never an OOM (review finding 3).
     */
    private fun gunzip(bytes: ByteArray): ByteArray? =
        try {
            InflaterInputStream(bytes.inputStream(), Inflater()).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(INFLATE_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > MAX_INFLATED_BYTES) return null // over the cap — reject, never OOM
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } catch (_: java.util.zip.ZipException) {
            null // bad gzip stream — reject-don't-guess
        } catch (_: java.io.IOException) {
            null
        }

    private fun decodeBase64Url(text: String): ByteArray? =
        try {
            base64UrlDecoder.decode(text)
        } catch (_: IllegalArgumentException) {
            null // bad base64url — reject-don't-guess
        }

    /** The typed decode result — [Decoded] on success, [Rejected] for any malformed / foreign token. */
    sealed interface Result {
        data class Decoded(val token: InviteToken) : Result

        /** Old v1 invite lacks authenticated access metadata; callers must reject and request a fresh token. */
        data object Legacy : Result

        data object Rejected : Result
    }

    companion object {
        /** The token scheme prefix. Decode rejects anything not starting with it (a foreign QR). */
        const val PREFIX = "owdm1:"

        /**
         * The hard cap on inflated invite-payload size. A real `owdm1:` payload is a few hundred bytes; 64 KB
         * is far above any legitimate token yet bounds the decompression-bomb DoS surface (review finding 3).
         */
        internal const val MAX_INFLATED_BYTES = 64 * 1024

        /** The read-buffer size for the bounded inflate loop. */
        private const val INFLATE_BUFFER_BYTES = 4096

        /** Version 2 adds signed access metadata; v1 is identified only to produce fresh-invite guidance. */
        private const val FORMAT_VERSION = "2"
        private const val LEGACY_FORMAT_VERSION = "1"
        private const val SIGNATURE_DOMAIN = "owdm/invite/access/v2"

        private const val KEY_VERSION = "v"
        private const val KEY_BASE_URL = "u"
        private const val KEY_USERNAME = "n"
        private const val KEY_APP_PASSWORD = "p"
        private const val KEY_CHAT_ROOT = "r"
        private const val KEY_CHAT_ID = "c"
        private const val KEY_CHAT_KEY = "k"
        private const val KEY_COMMUNITY = "m"
        private const val KEY_ACCESS = "a"
        private const val KEY_SIGNER = "s"
        private const val KEY_SIGNATURE = "g"
        private val CURRENT_FIELDS =
            setOf(
                KEY_VERSION,
                KEY_BASE_URL,
                KEY_USERNAME,
                KEY_APP_PASSWORD,
                KEY_CHAT_ROOT,
                KEY_CHAT_ID,
                KEY_CHAT_KEY,
                KEY_COMMUNITY,
                KEY_ACCESS,
                KEY_SIGNER,
                KEY_SIGNATURE,
            )

        // URL-safe base64 (RFC 4648 §5) WITHOUT padding — the token travels in a QR / a copied string,
        // where '+' '/' '=' are awkward; url-safe + no-pad keeps it compact and copy-clean.
        private val base64Url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val base64UrlDecoder: Base64.Decoder = Base64.getUrlDecoder()
    }
}
