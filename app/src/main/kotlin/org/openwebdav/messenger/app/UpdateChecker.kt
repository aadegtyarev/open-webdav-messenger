package org.openwebdav.messenger.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer version and downloads the APK.
 *
 * Version comparison is simple string-based (semantic versions like "v0.14.0" vs "0.14.0").
 * The GitHub tag is expected to be `vX.Y.Z`; the app's versionName is `X.Y.Z` (no "v" prefix).
 */
internal object UpdateChecker {
    private const val RELEASES_URL =
        "https://api.github.com/repos/aadegtyarev/open-webdav-messenger/releases/latest"

    data class UpdateInfo(
        val latestVersion: String,
        val apkUrl: String,
        val body: String,
        val isNewer: Boolean,
    )

    /** Check GitHub Releases for a version newer than [currentVersion] (e.g. "0.14.0"). */
    suspend fun check(currentVersion: String): Result<UpdateInfo> =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                val json = JSONObject(connection.inputStream.bufferedReader().readText())
                connection.disconnect()

                val tag = json.getString("tag_name").removePrefix("v")
                val assets = json.getJSONArray("assets")
                var apkUrl = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }
                val body = json.optString("body", "")

                Result.success(
                    UpdateInfo(
                        latestVersion = tag,
                        apkUrl = apkUrl,
                        body = body,
                        isNewer = compareVersions(tag, currentVersion) > 0,
                    ),
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Download the APK from [url] to a cache file. Returns the file on success. */
    suspend fun downloadApk(
        context: Context,
        url: String,
    ): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                val apkFile = File(context.externalCacheDir ?: context.cacheDir, "update.apk")
                FileOutputStream(apkFile).use { out ->
                    connection.inputStream.use { it.copyTo(out) }
                }
                connection.disconnect()
                Result.success(apkFile)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Open the APK file with the system package installer. */
    fun installApk(
        context: Context,
        apkFile: File,
    ) {
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }

    /** Compare two version strings like "0.14.0". Returns positive if a > b. */
    private fun compareVersions(
        a: String,
        b: String,
    ): Int {
        val aParts = a.split(".").map { it.toIntOrNull() ?: 0 }
        val bParts = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(aParts.size, bParts.size)) {
            val av = aParts.getOrElse(i) { 0 }
            val bv = bParts.getOrElse(i) { 0 }
            if (av != bv) return av - bv
        }
        return 0
    }
}
