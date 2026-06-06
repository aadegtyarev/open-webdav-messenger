package org.openwebdav.messenger.ui.scan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/**
 * A Compose wrapper over the `zxing-android-embedded` scanner view (`ui-chat-surface` arch note Choice 5;
 * stack-notes QR-scan: "wrap its view in a Compose `AndroidView`"). It is shown only once the CAMERA
 * permission is granted (the JoinScreen gates it) and only decodes QR symbols.
 *
 * Lifecycle: `resume()` on attach and `pause()` on dispose (a `View`-based scanner leaks the camera if not
 * paused — stack-notes QR-scan lifecycle). On a decoded symbol it fires [onDecoded] once and pauses, so the
 * caller can hand the string to the join path without the camera continuing to fire.
 *
 * The live camera decode itself is a **manual on-device step** (the same class as `connectedAndroidTest`,
 * decision 8) — there is no CI emulator with a camera; the JVM-testable decode lives in the codec/QR tests.
 */
@Composable
internal fun QrScannerView(
    onDecoded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val callback =
        remember {
            object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult?) {
                    result?.text?.let(onDecoded)
                }
            }
        }
    val scannerView =
        remember(callback) { mutableScannerHolder() }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            DecoratedBarcodeView(context).apply {
                barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                decodeSingle(callback)
                resume()
                scannerView.value = this
            }
        },
        onRelease = { view -> view.pause() },
    )
    // Tie resume/pause to the composable lifecycle so the camera is released on dispose.
    DisposableEffect(Unit) {
        onDispose { scannerView.value?.pause() }
    }
}

/** A tiny mutable holder so the [DisposableEffect] can pause the same view the factory built. */
private fun mutableScannerHolder() = androidx.compose.runtime.mutableStateOf<DecoratedBarcodeView?>(null)
