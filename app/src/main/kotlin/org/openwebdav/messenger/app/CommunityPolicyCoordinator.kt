package org.openwebdav.messenger.app

/** A community's combined policy values, retained while field-level edits are in flight. */
internal data class CommunityPolicy(val retentionDays: Int, val pollFloorSeconds: Int)

internal data class CommunityPolicyRequest(val communityId: String, val revision: Long, val policy: CommunityPolicy)

/** Merges rapid edits to different policy controls into one latest combined value. */
internal class CommunityPolicyCoordinator {
    private val pending = mutableMapOf<String, CommunityPolicyRequest>()
    private val revisions = mutableMapOf<String, Long>()

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
        val revision = (revisions[id] ?: 0L) + 1L
        revisions[id] = revision
        return CommunityPolicyRequest(id, revision, update(base)).also { pending[id] = it }
    }
}
