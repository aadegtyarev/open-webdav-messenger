package org.openwebdav.messenger.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.keystore.AccountIdentifier
import org.openwebdav.messenger.transport.PathSafety

class RestoreInputValidationTest {
    @Test
    fun restore_reuses_webdav_root_and_store_identifier_boundaries() {
        val config = ExportTestSupport.sampleConfig()
        assertTrue(PathSafety.isValidConnectionConfig(config))
        assertFalse(PathSafety.isValidConnectionConfig(config.copy(chatRoot = "root/%bad")))
        assertTrue(AccountIdentifier.isValid("a".repeat(AccountIdentifier.MAX_LENGTH)))
        assertFalse(AccountIdentifier.isValid("a".repeat(AccountIdentifier.MAX_LENGTH + 1)))
    }
}
