package org.openwebdav.messenger.app

import org.openwebdav.messenger.account.AccountMutationBarrier

/** A community's combined policy values, retained while field-level edits are in flight. */
internal data class CommunityPolicy(val retentionDays: Int, val pollFloorSeconds: Int)

internal data class CommunityPolicyRequest(val communityId: String, val revision: Long, val policy: CommunityPolicy)

/** Merges rapid edits to different policy controls into one latest combined value. */
internal class CommunityPolicyCoordinator {
    private val pending = mutableMapOf<String, CommunityPolicyRequest>()
    private var revisionSequence = 0L

    @Synchronized
    fun retention(
        id: String,
        committed: CommunityPolicy,
        days: Int,
    ): CommunityPolicyRequest =
        submit(id, committed) {
            it.copy(retentionDays = days.coerceIn(CommunityMetadata.MIN_RETENTION_DAYS, CommunityMetadata.MAX_RETENTION_DAYS))
        }

    @Synchronized
    fun pollFloor(
        id: String,
        committed: CommunityPolicy,
        seconds: Int,
    ): CommunityPolicyRequest =
        submit(id, committed) {
            it.copy(pollFloorSeconds = seconds.coerceIn(CommunityMetadata.MIN_FLOOR_SECONDS, CommunityMetadata.MAX_FLOOR_SECONDS))
        }

    @Synchronized
    fun defaults(id: String): CommunityPolicyRequest =
        submit(
            id,
            CommunityPolicy(CommunityMetadata.DEFAULT_RETENTION_DAYS, CommunityMetadata.DEFAULT_FLOOR_SECONDS),
        ) { it }

    suspend fun runInitialWrites(
        request: CommunityPolicyRequest?,
        expectedRuntimeGeneration: String,
        currentRuntimeGeneration: () -> String?,
        writeRoster: suspend () -> Unit,
        writePolicy: suspend (CommunityPolicy) -> Boolean,
        commitPolicy: (CommunityPolicy) -> Unit,
    ): Boolean =
        AccountMutationBarrier.process.withExclusive {
            if (currentRuntimeGeneration() != expectedRuntimeGeneration) {
                request?.let(::complete)
                return@withExclusive false
            }
            try {
                writeRoster()
                val initialPolicy = request ?: return@withExclusive true
                if (!isLatest(initialPolicy)) return@withExclusive true
                val written = writePolicy(initialPolicy.policy)
                if (written && isLatest(initialPolicy)) commitPolicy(initialPolicy.policy)
                written
            } finally {
                request?.let(::complete)
            }
        }

    @Synchronized
    fun reset() {
        pending.clear()
    }

    @Synchronized
    fun isLatest(request: CommunityPolicyRequest): Boolean = pending[request.communityId] == request

    @Synchronized
    fun complete(request: CommunityPolicyRequest) {
        if (isLatest(request)) pending.remove(request.communityId)
    }

    @Synchronized
    private fun submit(
        id: String,
        committed: CommunityPolicy,
        update: (CommunityPolicy) -> CommunityPolicy,
    ): CommunityPolicyRequest {
        val base = pending[id]?.policy ?: committed
        revisionSequence = Math.incrementExact(revisionSequence)
        return CommunityPolicyRequest(id, revisionSequence, update(base)).also { pending[id] = it }
    }
}
