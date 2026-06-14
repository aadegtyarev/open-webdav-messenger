package org.openwebdav.messenger.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.openwebdav.messenger.R
import org.openwebdav.messenger.crypto.LazySodiumCrypto
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.export.ExportManager
import org.openwebdav.messenger.export.ExportResult
import org.openwebdav.messenger.export.RestoreManager
import org.openwebdav.messenger.export.RestoreResult
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.identity.IdentityStore
import org.openwebdav.messenger.keystore.ChatKeyStore
import org.openwebdav.messenger.keystore.CommunityKeyStore
import org.openwebdav.messenger.keystore.ConnectionConfigStore
import java.io.File

/**
 * Minimal UI for account export and restore. Two modes: Export (password prompt → share blob) and
 * Restore (paste blob + password → restore). Placed as a standalone activity reachable directly
 * since no settings / onboarding screen exists yet.
 *
 * All blocking crypto work runs on [Dispatchers.IO] via [scope]; the UI reacts to typed results
 * on [Dispatchers.Main].
 */
class ExportRestoreActivity : Activity() {
    private lateinit var native: NativeCrypto
    private lateinit var connectionConfigStore: ConnectionConfigStore
    private lateinit var communityKeyStore: CommunityKeyStore
    private lateinit var chatKeyStore: ChatKeyStore
    private lateinit var identityStore: IdentityStore
    private lateinit var exportManager: ExportManager
    private lateinit var restoreManager: RestoreManager

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // UI elements
    private lateinit var exportTab: Button
    private lateinit var restoreTab: Button
    private lateinit var exportPanel: LinearLayout
    private lateinit var restorePanel: LinearLayout
    private lateinit var exportPassword: EditText
    private lateinit var exportPasswordConfirm: EditText
    private lateinit var exportButton: Button
    private lateinit var exportStatus: TextView
    private lateinit var restoreBlob: EditText
    private lateinit var restorePassword: EditText
    private lateinit var restoreButton: Button
    private lateinit var restoreStatus: TextView
    private lateinit var resultScroll: ScrollView
    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export_restore)

        bindViews()
        initDependencies()
        setupTabs()
        setupExport()
        setupRestore()
        showExportPanel()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // -- init ----------------------------------------------------------------

    private fun bindViews() {
        exportTab = findViewById(R.id.exportTab)
        restoreTab = findViewById(R.id.restoreTab)
        exportPanel = findViewById(R.id.exportPanel)
        restorePanel = findViewById(R.id.restorePanel)
        exportPassword = findViewById(R.id.exportPassword)
        exportPasswordConfirm = findViewById(R.id.exportPasswordConfirm)
        exportButton = findViewById(R.id.exportButton)
        exportStatus = findViewById(R.id.exportStatus)
        restoreBlob = findViewById(R.id.restoreBlob)
        restorePassword = findViewById(R.id.restorePassword)
        restoreButton = findViewById(R.id.restoreButton)
        restoreStatus = findViewById(R.id.restoreStatus)
        resultScroll = findViewById(R.id.resultScroll)
        resultText = findViewById(R.id.resultText)
    }

    private fun initDependencies() {
        // Single shared NativeCrypto — both the export/restore managers and the stores share it
        // (LazySodiumAndroid owns the native .so binding; one instance per process is fine).
        native = LazySodiumCrypto(LazySodiumAndroid(SodiumAndroid()))
        connectionConfigStore = ConnectionConfigStore(applicationContext)
        communityKeyStore = CommunityKeyStore(applicationContext)
        chatKeyStore = ChatKeyStore(applicationContext, native)
        identityStore = IdentityStore(applicationContext, IdentityCrypto(native))
        exportManager = ExportManager(native, connectionConfigStore, communityKeyStore, chatKeyStore, identityStore)
        restoreManager = RestoreManager(native, connectionConfigStore, communityKeyStore, chatKeyStore, identityStore)
    }

    // -- tabs ----------------------------------------------------------------

    private fun setupTabs() {
        exportTab.setOnClickListener { showExportPanel() }
        restoreTab.setOnClickListener { showRestorePanel() }
    }

    private fun showExportPanel() {
        exportPanel.visibility = View.VISIBLE
        restorePanel.visibility = View.GONE
        exportTab.isEnabled = false
        restoreTab.isEnabled = true
    }

    private fun showRestorePanel() {
        exportPanel.visibility = View.GONE
        restorePanel.visibility = View.VISIBLE
        exportTab.isEnabled = true
        restoreTab.isEnabled = false
    }

    // -- export --------------------------------------------------------------

    private fun setupExport() {
        exportButton.setOnClickListener {
            val pw = exportPassword.text.toString()
            val pwConfirm = exportPasswordConfirm.text.toString()

            if (pw.length < ExportManager.MIN_PASSPHRASE_LENGTH) {
                showExportStatus(getString(R.string.export_weak_password), isError = true)
                return@setOnClickListener
            }
            if (pw != pwConfirm) {
                showExportStatus(getString(R.string.export_mismatch), isError = true)
                return@setOnClickListener
            }

            exportButton.isEnabled = false
            showExportStatus("Encrypting…", isError = false)

            scope.launch(Dispatchers.IO) {
                val result = exportManager.export(pw.toCharArray())
                launch(Dispatchers.Main) {
                    exportButton.isEnabled = true
                    handleExportResult(result)
                }
            }
        }
    }

    private fun handleExportResult(result: ExportResult) {
        when (result) {
            is ExportResult.Ready -> {
                showExportStatus(getString(R.string.export_success), isError = false)
                shareBlob(result.blob)
            }
            ExportResult.WeakPassword -> {
                showExportStatus(getString(R.string.export_weak_password), isError = true)
            }
        }
    }

    private fun shareBlob(blob: String) {
        // Write the blob to a temp file and share via ACTION_SEND.
        val file = File(cacheDir, "owdm-export.txt")
        file.writeText(blob)

        val uri =
            FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file,
            )

        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, blob)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        startActivity(Intent.createChooser(intent, getString(R.string.export_restore_title)))
    }

    // -- restore -------------------------------------------------------------

    private fun setupRestore() {
        restoreButton.setOnClickListener {
            val blob = restoreBlob.text.toString().trim()
            val pw = restorePassword.text.toString()

            if (blob.isEmpty()) {
                showRestoreStatus(getString(R.string.restore_bad_format), isError = true)
                return@setOnClickListener
            }
            if (pw.length < ExportManager.MIN_PASSPHRASE_LENGTH) {
                showRestoreStatus(getString(R.string.restore_weak_password), isError = true)
                return@setOnClickListener
            }

            restoreButton.isEnabled = false
            showRestoreStatus("Decrypting…", isError = false)

            scope.launch(Dispatchers.IO) {
                val result = restoreManager.restore(blob, pw.toCharArray())
                launch(Dispatchers.Main) {
                    restoreButton.isEnabled = true
                    handleRestoreResult(result)
                }
            }
        }
    }

    private fun handleRestoreResult(result: RestoreResult) {
        when (result) {
            RestoreResult.Restored -> {
                showRestoreStatus(getString(R.string.restore_success), isError = false)
            }
            RestoreResult.BadFormat -> {
                showRestoreStatus(getString(R.string.restore_bad_format), isError = true)
            }
            RestoreResult.WrongPasswordOrTampered -> {
                showRestoreStatus(getString(R.string.restore_wrong_password), isError = true)
            }
            RestoreResult.CorruptPayload -> {
                showRestoreStatus(getString(R.string.restore_corrupt), isError = true)
            }
            RestoreResult.WeakPassword -> {
                showRestoreStatus(getString(R.string.restore_weak_password), isError = true)
            }
        }
    }

    // -- status helpers ------------------------------------------------------

    private fun showExportStatus(
        msg: String,
        isError: Boolean,
    ) {
        exportStatus.text = msg
        exportStatus.setTextColor(if (isError) 0xFFFF0000.toInt() else 0xFF008000.toInt())
        exportStatus.visibility = View.VISIBLE
    }

    private fun showRestoreStatus(
        msg: String,
        isError: Boolean,
    ) {
        restoreStatus.text = msg
        restoreStatus.setTextColor(if (isError) 0xFFFF0000.toInt() else 0xFF008000.toInt())
        restoreStatus.visibility = View.VISIBLE
    }

    companion object {
        private const val TAG = "ExportRestore"
    }
}
