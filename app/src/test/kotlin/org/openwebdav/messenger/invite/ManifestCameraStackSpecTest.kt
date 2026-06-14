package org.openwebdav.messenger.invite

import android.content.Context
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Stack-spec test `manifest_declares_camera_not_required` (`ui-chat-surface` plan Stack expectations / camera).
 * Pins two manifest facts on the MERGED manifest (what actually installs): the `CAMERA` runtime permission is
 * declared, and `android.hardware.camera.any` is declared **not required** — so the app stays installable on
 * camera-less devices and QR scanning is an optional enhancement, with paste the mandated fallback. Without
 * this pin, a later edit could silently flip the camera to required and break installability — exactly the
 * regression this test exists to catch.
 * Source: <https://developer.android.com/guide/topics/manifest/uses-feature-element> ;
 * <https://developer.android.com/training/permissions/requesting>
 */
@RunWith(RobolectricTestRunner::class)
class ManifestCameraStackSpecTest {
    private fun packageManager(): PackageManager = ApplicationProvider.getApplicationContext<Context>().packageManager

    private fun packageName(): String = ApplicationProvider.getApplicationContext<Context>().packageName

    /** The CAMERA dangerous permission is declared (needed for the optional QR-scan join path). */
    @Test
    fun manifest_declares_camera_permission() {
        val info = packageManager().getPackageInfo(packageName(), PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions?.toList().orEmpty()
        assertTrue(
            "AndroidManifest must declare android.permission.CAMERA (QR-scan join path)",
            requested.contains(android.Manifest.permission.CAMERA),
        )
    }

    /** `android.hardware.camera.any` is declared with required=false — installable on camera-less devices. */
    @Test
    fun manifest_declares_camera_not_required() {
        val info = packageManager().getPackageInfo(packageName(), PackageManager.GET_CONFIGURATIONS)
        val cameraFeature =
            info.reqFeatures?.firstOrNull { it.name == "android.hardware.camera.any" }
        assertNotNull(
            "AndroidManifest must declare <uses-feature android:name=\"android.hardware.camera.any\"/>",
            cameraFeature,
        )
        // FLAG_REQUIRED unset ⇒ required=false ⇒ the app stays installable without a camera.
        val isRequired = (cameraFeature!!.flags and FeatureInfo.FLAG_REQUIRED) != 0
        assertTrue(
            "camera.any must be android:required=\"false\" (QR scan is optional; paste is the fallback)",
            !isRequired,
        )
    }
}
