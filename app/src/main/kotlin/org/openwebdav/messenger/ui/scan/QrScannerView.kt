package org.openwebdav.messenger.ui.scan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
 * Lifecycle: `resume()` on `ON_START` and `pause()` on `ON_STOP`, wired to the host lifecycle — NOT only on
 * dispose. Backgrounding the scanner (the screen stays in the back stack, so the composable is not disposed)
 * fires `ON_STOP`, which releases the camera; coming back fires `ON_START`, which re-acquires it. Pausing
 * only on dispose left the camera held in the background — indicator on, battery drain, black preview on
 * resume (review finding 9; stack-notes QR-scan lifecycle). On a decoded symbol it fires [onDecoded] once.
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
    val scannerView = remember(callback) { mutableScannerHolder() }
    val lifecycleOwner = LocalLifecycleOwner.current

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

    // Tie resume/pause to the host lifecycle so the camera is released whenever the screen is backgrounded
    // (ON_STOP), not just when the composable is disposed.
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> scannerView.value?.resume()
                    Lifecycle.Event.ON_STOP -> scannerView.value?.pause()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            scannerView.value?.pause()
        }
    }
}

/** A tiny mutable holder so the lifecycle observer can pause/resume the same view the factory built. */
private fun mutableScannerHolder() = androidx.compose.runtime.mutableStateOf<DecoratedBarcodeView?>(null)
