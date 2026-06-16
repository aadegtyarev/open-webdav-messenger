package org.openwebdav.messenger.app

import org.json.JSONObject
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.transport.WebDavResult
import org.openwebdav.messenger.transport.WebDavTransport

/**
 * Community-scoped metadata stored in `meta/community.json` on the WebDAV disk,
 * written by the community host at creation and read by every member during poll cycles.
 *
 * The file is **not** content-addressed (unlike `log/` message files) — it is a simple named JSON file
 * whose integrity is protected by the host's Ed25519 signature. Any member can verify the signature
 * with the host's public key.
 *
 * **Client-enforced floor:** the [minPollIntervalMinutes] floor is enforced cooperatively by each
 * member's client. A modified client can bypass it — there is no server-side enforcement on a dumb
 * file disk. The floor is a governance primitive, not a security boundary (the flat-trust model, SC11,
 * already grants every member read/write/delete on the shared disk).
 *
 * @param minPollIntervalMinutes the community-wide minimum poll interval in minutes (1..1440).
 * @param retentionWindowDays how many days of message history to keep (7..90, default 14).
 */
internal data class CommunityMetadata(
    val minPollIntervalMinutes: Int,
    val retentionWindowDays: Int = DEFAULT_RETENTION_DAYS,
) {
    companion object {
        /** Default community floor: 15 minutes (matches WorkManager's platform floor). */
        const val DEFAULT_FLOOR_MINUTES = 15

        /** Maximum allowed floor: 1 day (prevents accidental lock-out). */
        const val MAX_FLOOR_MINUTES = 1440

        /** Minimum allowed floor: 1 minute. */
        const val MIN_FLOOR_MINUTES = 1

        /** Default retention window: 14 days. */
        const val DEFAULT_RETENTION_DAYS = 14

        /** Minimum retention window: 7 days. */
        const val MIN_RETENTION_DAYS = 7

        /** Maximum retention window: 90 days. */
        const val MAX_RETENTION_DAYS = 90

        /** The file path on the WebDAV disk (under the community root, next to `meta/roster.json`). */
        const val FILE_PATH = "meta/community.json"

        /**
         * Read [CommunityMetadata] from the WebDAV disk via [transport], verifying the host's Ed25519
         * [signature] with [identityCrypto] against [hostSignPublicKey] (the host's Ed25519 public key,
         * 32 bytes).
         *
         * Returns the metadata on success, or `null` when the file is missing, unreadable, tampered
         * (signature fails), or the content is structurally invalid. The caller MUST fall back to
         * [DEFAULT_FLOOR_MINUTES] on null — fail-closed, never relax below the default.
         *
         * The signature covers the raw JSON bytes BEFORE parsing, so the verify-before-parse order
         * prevents any JSON-parser-level attack from bypassing the signature check.
         */
        suspend fun read(
            transport: WebDavTransport,
            identityCrypto: IdentityCrypto,
            hostSignPublicKey: ByteArray,
        ): CommunityMetadata? {
            val rawRead = transport.readRaw(FILE_PATH)
            if (rawRead !is WebDavResult.Success) return null
            val bytes = rawRead.value
            if (bytes.isEmpty()) return null

            // Verify the host's signature BEFORE parsing JSON — any tampered content is rejected
            // before the parser ever sees it.
            val sigBytes = extractSignature(bytes) ?: return null
            val payloadBytes = extractPayload(bytes) ?: return null
            if (!identityCrypto.verify(sigBytes, payloadBytes, hostSignPublicKey)) return null

            return parsePayload(payloadBytes)
        }

        /**
         * Write [metadata] to the WebDAV disk via [transport], signed by [hostIdentity]'s Ed25519
         * signing key. Ensures the `meta/` collection exists first.
         *
         * The on-disk format is:
         *   signature (64 bytes, Ed25519 detached) ‖ payload (UTF-8 JSON)
         *
         * This simple concatenation (sig ‖ payload) avoids a framing layer and is deterministic —
         * the signature covers exactly the JSON payload bytes.
         */
        suspend fun write(
            transport: WebDavTransport,
            metadata: CommunityMetadata,
            hostIdentity: Identity,
            identityCrypto: IdentityCrypto,
        ) {
            val json = metadata.toJson()
            val payloadBytes = json.toByteArray(Charsets.UTF_8)
            val signSecret = hostIdentity.copySignSecret()
            try {
                val signature = identityCrypto.sign(payloadBytes, signSecret)
                val fileBytes = signature + payloadBytes
                transport.ensureCollection("meta")
                transport.write(FILE_PATH, fileBytes)
            } finally {
                signSecret.fill(0)
            }
        }

        /** JSON → [CommunityMetadata], clamping to valid range. Returns null on parse failure. */
        private fun parsePayload(bytes: ByteArray): CommunityMetadata? {
            return try {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                val minutes =
                    json.getInt("minPollIntervalMinutes")
                        .coerceIn(MIN_FLOOR_MINUTES, MAX_FLOOR_MINUTES)
                val retentionDays =
                    json.optInt("retentionWindowDays", DEFAULT_RETENTION_DAYS)
                        .coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)
                CommunityMetadata(minPollIntervalMinutes = minutes, retentionWindowDays = retentionDays)
            } catch (_: Exception) {
                null
            }
        }

        /** Extract the 64-byte Ed25519 signature from the front of [bytes]. */
        private fun extractSignature(bytes: ByteArray): ByteArray? {
            if (bytes.size < 64) return null
            return bytes.copyOfRange(0, 64)
        }

        /** Extract the payload (everything after the 64-byte signature). */
        private fun extractPayload(bytes: ByteArray): ByteArray? {
            if (bytes.size <= 64) return null
            return bytes.copyOfRange(64, bytes.size)
        }

        /**
         * The one true source for the community minimum poll interval, factoring in remote metadata.
         * Returns the effective floor: max of the [remoteFloor] and [DEFAULT_FLOOR_MINUTES].
         * Fail-closed: if [remoteFloor] is null, defaults to [DEFAULT_FLOOR_MINUTES].
         */
        fun floorMinutes(remoteFloor: Int?): Int {
            return maxOf(remoteFloor ?: DEFAULT_FLOOR_MINUTES, DEFAULT_FLOOR_MINUTES)
        }
    }

    /** Serialize to JSON payload (NOT including signature). */
    private fun toJson(): String {
        return JSONObject().apply {
            put("minPollIntervalMinutes", minPollIntervalMinutes)
            put("retentionWindowDays", retentionWindowDays)
        }.toString()
    }
}
