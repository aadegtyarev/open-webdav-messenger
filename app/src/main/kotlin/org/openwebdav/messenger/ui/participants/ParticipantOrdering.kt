package org.openwebdav.messenger.ui.participants

import org.openwebdav.messenger.app.VerifiedParticipant
import java.util.Locale

internal fun orderedParticipants(rows: List<VerifiedParticipant>): List<VerifiedParticipant> =
    rows.sortedWith(
        compareByDescending<VerifiedParticipant> { it.isSelf }
            .thenBy { it.displayName.trim().lowercase(Locale.ROOT) }
            .thenBy { it.fingerprint },
    )
