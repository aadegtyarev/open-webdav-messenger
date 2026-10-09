package org.openwebdav.messenger.data

sealed interface DmLocalDeleteResult {
    data object Deleted : DmLocalDeleteResult

    data object AlreadyHidden : DmLocalDeleteResult

    data object SuppressionLimitReached : DmLocalDeleteResult
}
