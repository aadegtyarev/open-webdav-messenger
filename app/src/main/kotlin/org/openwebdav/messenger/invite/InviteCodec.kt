package org.openwebdav.messenger.invite

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Encode [token] to the `owdm1:<base64url(gzip(json))>` string, off the UI thread. */
    suspend fun encode(token: InviteToken): String =
        withContext(ioDispatcher) {
            val json = FlatJson.encode(toFields(token))
            val gzipped = gzip(json.toByteArray(Charsets.UTF_8))
            PREFIX + base64Url.encodeToString(gzipped)
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
        return fromFields(fields)?.let { Result.Decoded(it) } ?: Result.Rejected
    }

    private fun toFields(token: InviteToken): Map<String, String> =
        linkedMapOf(
            KEY_VERSION to FORMAT_VERSION,
            KEY_BASE_URL to token.baseUrl,
            KEY_USERNAME to token.username,
            KEY_APP_PASSWORD to token.appPassword,
            KEY_CHAT_ROOT to token.chatRoot,
            KEY_CHAT_ID to token.chatId,
            KEY_CHAT_KEY to base64Url.encodeToString(token.chatKey),
            KEY_COMMUNITY to token.communityName,
        )

    private fun fromFields(fields: Map<String, String>): InviteToken? {
        if (fields[KEY_VERSION] != FORMAT_VERSION) return null
        val rawKey = decodeBase64Url(fields[KEY_CHAT_KEY] ?: return null) ?: return null
        if (rawKey.size != InviteToken.CHAT_KEY_BYTES) return null
        return InviteToken(
            baseUrl = fields[KEY_BASE_URL] ?: return null,
            username = fields[KEY_USERNAME] ?: return null,
            appPassword = fields[KEY_APP_PASSWORD] ?: return null,
            chatRoot = fields[KEY_CHAT_ROOT] ?: return null,
            chatId = fields[KEY_CHAT_ID] ?: return null,
            chatKey = rawKey,
            communityName = fields[KEY_COMMUNITY] ?: return null,
        )
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray? =
        try {
            InflaterInputStream(bytes.inputStream(), Inflater()).use { it.readBytes() }
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

        data object Rejected : Result
    }

    companion object {
        /** The token scheme prefix. Decode rejects anything not starting with it (a foreign QR). */
        const val PREFIX = "owdm1:"

        /** The JSON `v` field value — a second version guard inside the payload (reject on mismatch). */
        private const val FORMAT_VERSION = "1"

        private const val KEY_VERSION = "v"
        private const val KEY_BASE_URL = "u"
        private const val KEY_USERNAME = "n"
        private const val KEY_APP_PASSWORD = "p"
        private const val KEY_CHAT_ROOT = "r"
        private const val KEY_CHAT_ID = "c"
        private const val KEY_CHAT_KEY = "k"
        private const val KEY_COMMUNITY = "m"

        // URL-safe base64 (RFC 4648 §5) WITHOUT padding — the token travels in a QR / a copied string,
        // where '+' '/' '=' are awkward; url-safe + no-pad keeps it compact and copy-clean.
        private val base64Url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val base64UrlDecoder: Base64.Decoder = Base64.getUrlDecoder()
    }
}
