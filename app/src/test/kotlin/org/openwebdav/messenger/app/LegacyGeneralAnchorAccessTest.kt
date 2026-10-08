package org.openwebdav.messenger.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openwebdav.messenger.keystore.ChatRegistry

class LegacyGeneralAnchorAccessTest {
    @Test
    fun only_exact_unknown_general_anchor_migrates_to_public() {
        val rows = listOf(ChatRegistry.Entry("anchor", "General", "general", "unknown"))

        assertEquals(
            "public",
            LegacyGeneralAnchorAccess.migrate("anchor", "anchor", "anchor", rows)?.single()?.access,
        )
    }

    @Test
    fun unknown_groups_and_ambiguous_anchor_relationships_remain_unresolved() {
        val group = listOf(ChatRegistry.Entry("anchor", "Group", "group", "unknown"))
        val general = listOf(ChatRegistry.Entry("anchor", "General", "general", "unknown"))

        assertNull(LegacyGeneralAnchorAccess.migrate("anchor", "anchor", "anchor", group))
        assertNull(LegacyGeneralAnchorAccess.migrate("other-anchor", "anchor", "anchor", general))
        assertNull(LegacyGeneralAnchorAccess.migrate("anchor", "other-anchor", "anchor", general))
        assertNull(LegacyGeneralAnchorAccess.migrate("anchor", "anchor", "anchor", general + general))
    }
}
