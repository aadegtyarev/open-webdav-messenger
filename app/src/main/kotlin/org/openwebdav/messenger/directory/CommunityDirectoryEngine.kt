package org.openwebdav.messenger.directory

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.protocol.Envelope
import org.openwebdav.messenger.transport.InboxEntry
import org.openwebdav.messenger.transport.ReadResult
import org.openwebdav.messenger.transport.WebDavResult
import org.openwebdav.messenger.transport.WebDavTransport

/**
 * The shared §10/§11 community-directory orchestration core (`docs/protocol/webdav-layout.md`). The §10
 * user directory and the §11 chat directory run the **same** publish (seal → ensure-collection → write)
 * and read (list → per-entry GET/open/verify → supersede-resolve) pipelines over the same
 * [WebDavTransport] verbs; they differ ONLY in the collection segment, the grouping key (verified
 * signing-pubkey hex for §10 vs chat-id hex for §11), the entry type, and the seal/open codec. This
 * engine is that one pipeline, parameterised; `DirectoryService` / `ChatDirectoryService` are thin
 * public faces over it that keep their own typed outcome/result types and §-specific privacy gates.
 *
 * Behaviour is byte-identical to the two former hand-written services: the §10.6/§11.6 flat-trust
 * degradation (every per-entry failure is a typed drop, never wedging the read), the SC16 name gate
 * before any GET, the explicit non-`none` codec reject, and the §10.5/§11.5 supersede resolution all
 * live here unchanged. The two collections never cross-wire — each call gets its own resolver and its
 * own [CollectionPaths] / verify function.
 *
 * @param E the verified entry type the consuming service surfaces.
 */
internal class CommunityDirectoryEngine<E>(
    private val transport: WebDavTransport,
    private val paths: CollectionPaths,
    /** Open + verify one envelope's bytes into a verified entry (with its grouping key + counter) or a drop. */
    private val verify: (communityKey: ChatKey, envelopeBytes: ByteArray) -> VerifyResult<E>,
) {
    /**
     * The shared publish sequence: AEAD-seal via [seal], then idempotent `MKCOL` of the collection and a
     * content-addressed `PUT`. The seal is run inside a narrow try/catch so an invalid-parameter
     * [IllegalArgumentException] or a native-seal [IllegalStateException] degrades to a typed failure
     * (the "never throws" publish contract, incl. C8). The result is mapped to the caller's outcome type
     * by the lambdas so each service keeps its own typed `*Outcome`.
     *
     * @param seal produce the complete envelope bytes to PUT (signs + seals); may throw IAE/ISE.
     * @param published map a successful content-addressed entry name to the caller's success outcome.
     * @param failed map a short diagnostic to the caller's failure outcome.
     */
    suspend fun <O> publish(
        seal: () -> ByteArray,
        published: (entryName: String) -> O,
        failed: (reason: String) -> O,
    ): O {
        val envelopeBytes =
            try {
                seal()
            } catch (e: IllegalArgumentException) {
                // The codec validates entry/descriptor parameters with require(); an invalid value
                // throws IAE. Convert to a typed failure so the "never throws" publish contract holds.
                return failed("invalid entry parameters: ${e.message}")
            } catch (e: IllegalStateException) {
                // A native AEAD seal failure surfaces as IllegalStateException from the seal path
                // (LazySodiumCrypto.aeadEncrypt's check() on a spurious libsodium encrypt failure; the
                // signature-width check() on the same path is the only other source). Map it to the same
                // typed failure so a runtime crypto-substrate failure degrades to a retryable failure
                // rather than propagating uncaught (C8). Caught narrowly — an unrelated bug still surfaces.
                return failed("entry seal failed: ${e.message}")
            }
        if (transport.ensureCollection(paths.collection) !is WebDavResult.Success) {
            return failed("could not ensure ${paths.collection}/ collection (transport back-off)")
        }
        val entryName = paths.entryName(envelopeBytes)
        return when (transport.write(paths.entryPath(entryName), envelopeBytes)) {
            is WebDavResult.Success -> published(entryName)
            else -> failed("could not write ${paths.collection} entry (transport back-off)")
        }
    }

    /**
     * The shared read sequence: one `PROPFIND Depth: 1` on the collection, then GET/open/verify each
     * entry and resolve the latest valid entry per grouping key (§10.5/§11.5). A listing failure yields
     * [ReadOutcome] with `listingFailed = true` (retry next cycle); a per-entry failure increments
     * `rejectedCount` and is dropped, never wedging the read (flat-trust degradation). Never throws.
     */
    suspend fun read(communityKey: ChatKey): ReadOutcome<E> {
        val entries =
            when (val listed = transport.list(paths.collection)) {
                is WebDavResult.Success -> listed.value
                else -> return ReadOutcome(entries = emptyList(), rejectedCount = 0, listingFailed = true)
            }
        val resolver = SupersedeResolver<E>()
        var rejected = 0
        for (entry in entries) {
            when (val outcome = fetchAndVerify(entry, communityKey)) {
                is FetchOutcome.Verified ->
                    resolver.offer(
                        groupingKey = outcome.groupingKey,
                        value = outcome.value,
                        versionCounter = outcome.versionCounter,
                        entryName = outcome.entryName,
                    )
                FetchOutcome.Dropped -> rejected++
                FetchOutcome.NotReady -> rejected++ // transient (incomplete upload / hash mismatch) — counted, retried next read
            }
        }
        return ReadOutcome(entries = resolver.resolved(), rejectedCount = rejected)
    }

    /**
     * Per-entry path: validate the name (SC16 / reject-don't-guess), `GET` with the §3 content-hash
     * check, re-frame, then hand the envelope bytes to the collection's [verify] (AEAD-open + parse +
     * §-specific verify/drop). A non-`none` codec is rejected explicitly (this feature inflates only
     * `codec-id = 0x00`).
     */
    private suspend fun fetchAndVerify(
        entry: InboxEntry,
        communityKey: ChatKey,
    ): FetchOutcome<E> {
        if (entry.isCollection) return FetchOutcome.Dropped
        // SC16 / reject-don't-guess: a name outside the §10.4/§11.4 grammar is dropped before any GET
        // (it is never dereferenced — path-traversal-safe by construction).
        if (!paths.isWellFormedEntryName(entry.name)) return FetchOutcome.Dropped
        val path = paths.entryPath(entry.name)
        val blobResult =
            when (val read = transport.readContentAddressed(path, entry.name)) {
                is WebDavResult.Success ->
                    when (val r = read.value) {
                        is ReadResult.Ready -> r
                        ReadResult.NotReady -> return FetchOutcome.NotReady
                    }
                else -> return FetchOutcome.NotReady // 429 / timeout / transport error → retry next read
            }
        if (blobResult.codecId != Envelope.CODEC_NONE) return FetchOutcome.Dropped
        val envelopeBytes = Envelope.frame(blobResult.codecId, blobResult.blob)
        return when (val verified = verify(communityKey, envelopeBytes)) {
            is VerifyResult.Verified ->
                FetchOutcome.Verified(
                    value = verified.value,
                    groupingKey = verified.groupingKey,
                    versionCounter = verified.versionCounter,
                    entryName = entry.name,
                )
            VerifyResult.Dropped -> FetchOutcome.Dropped // wrong key / bad sig / malformed / dm / invalid access
        }
    }

    /** One entry's read outcome inside [read]. */
    private sealed interface FetchOutcome<out E> {
        data class Verified<E>(
            val value: E,
            val groupingKey: String,
            val versionCounter: Long,
            val entryName: String,
        ) : FetchOutcome<E>

        /** Permanently rejected: wrong/absent key, bad signature, malformed, dm-kind, foreign name, bad codec. */
        data object Dropped : FetchOutcome<Nothing>

        /** Transient: incomplete upload / content-hash mismatch / transport back-off — retried next read. */
        data object NotReady : FetchOutcome<Nothing>
    }
}

/**
 * The outcome of [CommunityDirectoryEngine.verify]: a verified entry with its supersede grouping key +
 * counter (§10.5/§11.5), or a typed drop. The §-specific privacy gates (the §11 dm-drop, invalid-access)
 * map to [Dropped] inside the verify function, so the engine stays collection-agnostic.
 */
internal sealed interface VerifyResult<out E> {
    data class Verified<E>(val value: E, val groupingKey: String, val versionCounter: Long) : VerifyResult<E>

    data object Dropped : VerifyResult<Nothing>
}

/** The collection-agnostic read result: verified entries (latest per grouping key) + diagnostics. */
internal data class ReadOutcome<E>(
    val entries: List<E>,
    val rejectedCount: Int,
    val listingFailed: Boolean = false,
)
