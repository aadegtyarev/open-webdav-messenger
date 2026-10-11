package org.openwebdav.messenger.governance

internal sealed interface PolicyRevisionDecision {
    data object Accepted : PolicyRevisionDecision

    data object Duplicate : PolicyRevisionDecision

    data object UntrustedHost : PolicyRevisionDecision

    data object Invalid : PolicyRevisionDecision

    data object Rollback : PolicyRevisionDecision

    data object Equivocation : PolicyRevisionDecision
}
