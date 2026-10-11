package org.openwebdav.messenger.keystore

import android.content.Context
import java.io.File
import java.io.IOException

internal class EncryptedCommunityTrustStorage(context: Context) : CommunityTrustPersistence {
    private val wrapper = KeystoreWrapper(ALIAS, File(context.filesDir, FILE_NAME))

    override fun load(): List<org.openwebdav.messenger.governance.CommunityTrustRecord> =
        when (val unwrapped = wrapper.unwrap()) {
            UnwrapResult.None -> emptyList()
            is UnwrapResult.Unrecoverable -> throw IOException("Community trust store unavailable: ${unwrapped.reason}")
            is UnwrapResult.Unwrapped ->
                try {
                    CommunityTrustStoreCodec.decode(unwrapped.plaintext)
                        ?: throw IOException("Community trust store is malformed")
                } finally {
                    unwrapped.plaintext.fill(0)
                }
        }

    override fun save(records: List<org.openwebdav.messenger.governance.CommunityTrustRecord>) {
        val plain = CommunityTrustStoreCodec.encode(records)
        try {
            wrapper.wrapStrictAtomic(plain)
        } finally {
            plain.fill(0)
        }
    }

    private companion object {
        const val ALIAS = "owdm.community-trust.v1"
        const val FILE_NAME = "connconfig/community-trust.bin"
    }
}
