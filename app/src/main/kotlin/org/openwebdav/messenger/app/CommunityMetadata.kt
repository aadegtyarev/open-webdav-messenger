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
 * **Client-enforced floor:** the [minPollIntervalSeconds] floor is enforced cooperatively by each
 * member's client. A modified client can bypass it — there is no server-side enforcement on a dumb
 * file disk. The floor is a governance primitive, not a security boundary (the flat-trust model, SC11,
 * already grants every member read/write/delete on the shared disk).
 *
 * @param minPollIntervalSeconds the community-wide minimum poll interval in seconds (1..3600).
 * @param retentionWindowDays how many days of message history to keep (7..90, default 14).
 */
internal data class CommunityMetadata(
    val minPollIntervalSeconds: Int,
    val retentionWindowDays: Int = DEFAULT_RETENTION_DAYS,
) {
    companion object {
        /** Default community floor: 60 seconds. */
        const val DEFAULT_FLOOR_SECONDS = 60

        /** Maximum allowed floor: 3600 seconds (1 hour). */
        const val MAX_FLOOR_SECONDS = 3600

        /** Minimum allowed floor: 1 second. */
        const val MIN_FLOOR_SECONDS = 1

        /** Default retention window: 14 days. */
        const val DEFAULT_RETENTION_DAYS = 14

        /** Minimum retention window: 7 days. */
        const val MIN_RETENTION_DAYS = 7

        /** Maximum retention window: 90 days. */
        const val MAX_RETENTION_DAYS = 90

        /** The file path on the WebDAV disk (under the community root, next to `meta/roster.json`). */
        const val FILE_PATH = "meta/community.json"

        private const val SIGNATURE_BYTES = 64
        private const val HOST_KEY_BYTES = 32

        /**
         * Read [CommunityMetadata] from the WebDAV disk via [transport]. The host's Ed25519 public key
         * is embedded in the file (last 32 bytes, unsigned — accepted under flat-trust SC11), so no
         * out-of-band key resolution is needed.
         *
         * Returns the metadata on success, or `null` when the file is missing, unreadable, tampered
         * (signature fails), or the content is structurally invalid. The caller MUST fall back to
         * [DEFAULT_FLOOR_SECONDS] on null — fail-closed, never relax below the default.
         *
         * The signature covers the raw JSON bytes BEFORE parsing, so the verify-before-parse order
         * prevents any JSON-parser-level attack from bypassing the signature check.
         */
        suspend fun read(
            transport: WebDavTransport,
            identityCrypto: IdentityCrypto,
        ): CommunityMetadata? {
            val rawRead = transport.readRaw(FILE_PATH)
            if (rawRead !is WebDavResult.Success) return null
            val bytes = rawRead.value
            if (bytes.size < SIGNATURE_BYTES + HOST_KEY_BYTES) return null

            // On-disk format: signature(64) ‖ payload(JSON) ‖ hostSignPub(32)
            val sigBytes = bytes.copyOfRange(0, SIGNATURE_BYTES)
            val hostSignPub = bytes.copyOfRange(bytes.size - HOST_KEY_BYTES, bytes.size)
            val payloadBytes = bytes.copyOfRange(SIGNATURE_BYTES, bytes.size - HOST_KEY_BYTES)

            // Verify the host's signature BEFORE parsing JSON — any tampered content is rejected
            // before the parser ever sees it.
            if (!identityCrypto.verify(sigBytes, payloadBytes, hostSignPub)) return null

            return parsePayload(payloadBytes)
        }

        /**
         * Write [metadata] to the WebDAV disk via [transport], signed by [hostIdentity]'s Ed25519
         * signing key. Ensures the `meta/` collection exists first.
         *
         * The on-disk format is:
         *   signature (64 bytes, Ed25519 detached) ‖ payload (UTF-8 JSON) ‖ hostSignPub (32 bytes)
         *
         * The host's public key is embedded in the file (unsigned, last 32 bytes — accepted under
         * flat-trust SC11) so non-host members can verify the signature without out-of-band key
         * resolution.
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
            val hostSignPub = hostIdentity.copySignPublic()
            try {
                val signature = identityCrypto.sign(payloadBytes, signSecret)
                val fileBytes = signature + payloadBytes + hostSignPub
                transport.ensureCollection("meta")
                transport.write(FILE_PATH, fileBytes)
            } finally {
                signSecret.fill(0)
                hostSignPub.fill(0)
            }
        }

        /** JSON → [CommunityMetadata], clamping to valid range. Returns null on parse failure. */
        private fun parsePayload(bytes: ByteArray): CommunityMetadata? {
            return try {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                val seconds =
                    json.getInt("minPollIntervalSeconds")
                        .coerceIn(MIN_FLOOR_SECONDS, MAX_FLOOR_SECONDS)
                val retentionDays =
                    json.optInt("retentionWindowDays", DEFAULT_RETENTION_DAYS)
                        .coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)
                CommunityMetadata(minPollIntervalSeconds = seconds, retentionWindowDays = retentionDays)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * The one true source for the community minimum poll interval, factoring in remote metadata.
         * Returns the effective floor: max of the [remoteFloor] and [DEFAULT_FLOOR_SECONDS].
         * Fail-closed: if [remoteFloor] is null, defaults to [DEFAULT_FLOOR_SECONDS].
         */
        fun floorSeconds(remoteFloor: Int?): Int {
            return maxOf(remoteFloor ?: DEFAULT_FLOOR_SECONDS, DEFAULT_FLOOR_SECONDS)
        }
    }

    /** Serialize to JSON payload (NOT including signature). */
    private fun toJson(): String {
        return JSONObject().apply {
            put("minPollIntervalSeconds", minPollIntervalSeconds)
            put("retentionWindowDays", retentionWindowDays)
        }.toString()
    }
}
