// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.feature.key

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.nkvas1.zales.common.ZalesLog
import io.github.nkvas1.zales.design.Zales
import io.github.nkvas1.zales.design.component.PlateButton
import io.github.nkvas1.zales.design.component.ZalesText
import java.util.concurrent.Executors

/**
 * The camera, pointed at somebody else's screen.
 *
 * Asks for the camera only at the moment it is opened, never at startup: a VPN
 * that wants the camera the first time it is launched has a great deal of
 * explaining to do, and nobody reads the explanation.
 */
@Composable
internal fun QrScanner(onFound: (String) -> Unit, onGiveUp: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) ask.launch(Manifest.permission.CAMERA)
    }

    if (!granted) {
        ZalesText(
            text = stringResource(R.string.key_camera_refused),
            style = Zales.type.body,
            color = Zales.colors.rime,
        )
        PlateButton(text = stringResource(R.string.key_camera_back), onClick = onGiveUp)
        return
    }

    CameraEye(onFound, modifier)
    PlateButton(text = stringResource(R.string.key_camera_back), onClick = onGiveUp)
}

@Composable
private fun CameraEye(onFound: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onFound)
    val executor = remember { Executors.newSingleThreadExecutor() }
    // One key is all that is wanted; a second reading of the same code while
    // the camera is still being lowered would only confuse things.
    val taken = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    Box(modifier.fillMaxWidth().aspectRatio(1f)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            factory = { host ->
                val view = PreviewView(host).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val future = ProcessCameraProvider.getInstance(host)
                future.addListener({
                    runCatching {
                        val provider = future.get()
                        val preview = Preview.Builder().build()
                            .also { it.surfaceProvider = view.surfaceProvider }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { frame ->
                            frame.use { readOne(it, taken, latest) }
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }.onFailure { ZalesLog.warn(ZalesLog.TAG_UI, "could not open the camera", it) }
                }, ContextCompat.getMainExecutor(host))
                view
            },
        )
    }
}

/** Reads the luminance plane of one frame. Colour is of no interest to a QR code. */
private fun readOne(
    frame: ImageProxy,
    taken: java.util.concurrent.atomic.AtomicBoolean,
    onFound: (String) -> Unit,
) {
    if (taken.get()) return
    val plane = frame.planes.firstOrNull() ?: return
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val text = Qr.readFrame(bytes, plane.rowStride, frame.height) ?: return
    if (taken.compareAndSet(false, true)) onFound(text)
}
