// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nkvas1.zales.common.ZalesLog
import io.github.nkvas1.zales.design.ZalesTheme
import io.github.nkvas1.zales.feature.diagnostics.DiagnosticsScreen
import io.github.nkvas1.zales.feature.diagnostics.DiagnosticsViewModel
import io.github.nkvas1.zales.feature.home.HomeEvent
import io.github.nkvas1.zales.feature.home.HomeScreen
import io.github.nkvas1.zales.feature.home.HomeUiState
import io.github.nkvas1.zales.feature.home.HomeViewModel
import io.github.nkvas1.zales.feature.key.AndroidClipboard
import io.github.nkvas1.zales.feature.key.KeyMode
import io.github.nkvas1.zales.feature.key.KeyScreen
import io.github.nkvas1.zales.feature.key.KeyUiState
import io.github.nkvas1.zales.feature.key.KeyViewModel
import io.github.nkvas1.zales.feature.key.Qr
import io.github.nkvas1.zales.words.FailureAction

/** Where in the app we are. Three places, and no navigation library to say so. */
private enum class Place { HOME, KEY, CHECK }

/**
 * The single window. Three places to be: the switch, the key, and the check.
 */
public class MainActivity : ComponentActivity() {

    private val container: ZalesContainer by lazy { (application as ZalesApp).container }

    private val home: HomeViewModel by viewModels {
        HomeViewModel.factory(
            container.tunnel,
            container.keys,
            container.voice,
            BuildConfig.VERSION_NAME,
            container.hints,
        )
    }
    private val key: KeyViewModel by viewModels {
        KeyViewModel.factory(container.keys, AndroidClipboard(applicationContext))
    }
    private val check: DiagnosticsViewModel by viewModels {
        DiagnosticsViewModel.factory(container.tunnel, ReportWriter(applicationContext))
    }

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            home.onConsentGranted()
        } else {
            ZalesLog.info(ZalesLog.TAG_UI, "VPN consent declined")
            home.onEventHandled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            ZalesTheme {
                var place by remember { mutableStateOf(Place.HOME) }
                val homeState by home.state.collectAsStateWithLifecycle()
                val keyState by key.state.collectAsStateWithLifecycle()
                val event by home.event.collectAsStateWithLifecycle()

                LaunchedEffect(event) {
                    when (event) {
                        HomeEvent.AskVpnConsent -> {
                            container.tunnel.consentIntent()?.let(consent::launch) ?: home.onConsentGranted()
                        }
                        HomeEvent.OpenKeys -> {
                            place = Place.KEY
                            home.onEventHandled()
                        }
                        null -> Unit
                    }
                }

                when (place) {
                    Place.KEY -> Keys(keyState) { place = it }
                    Place.CHECK -> Check { place = it }
                    Place.HOME -> Home(homeState) { place = it }
                }
            }
        }
    }

    @Composable
    private fun Home(state: HomeUiState, go: (Place) -> Unit) {
        HomeScreen(
            state = state,
            onToggle = home::toggle,
            onAction = { action ->
                perform(action) { destination ->
                    if (destination == Place.CHECK) check.start()
                    go(destination)
                }
            },
            onHint = home::hint,
        )
    }

    @Composable
    private fun Keys(state: KeyUiState, go: (Place) -> Unit) {
        // A key is most often a screenshot in a messenger, so reading one out
        // of the gallery matters at least as much as the camera does.
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val text = uri?.let { readCode(it) }
            if (text != null) key.onScanned(text) else key.scanFoundNothing()
        }
        KeyScreen(
            state = state,
            onTextChange = key::onTextChange,
            onPasteClipboard = key::pasteClipboard,
            onSave = {
                key.save()
                home.refreshKeys()
            },
            onForget = { id ->
                key.forget(id)
                home.refreshKeys()
            },
            onScan = key::scan,
            onScanned = { text ->
                key.onScanned(text)
                home.refreshKeys()
            },
            onPickPicture = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onHandoff = key::showHandoff,
            onCloseOverlay = key::closeOverlay,
            modifier = Modifier,
        )
        BackHandler {
            if (state.mode == KeyMode.TEXT) go(Place.HOME) else key.closeOverlay()
        }
    }

    @Composable
    private fun Check(go: (Place) -> Unit) {
        val state by check.state.collectAsStateWithLifecycle()
        val leave = {
            check.leave()
            go(Place.HOME)
        }
        DiagnosticsScreen(
            state = state,
            onAction = { action -> perform(action, go) },
            onCopyReport = check::copyReport,
            onToggleReport = check::toggleReport,
            onRepeat = check::start,
            onLeave = leave,
        )
        BackHandler { leave() }
    }

    /**
     * Reads a code out of a picture the person chose.
     *
     * Decoded twice on purpose: once for its dimensions alone, then again
     * downsampled. A modern phone's screenshot is several times larger than any
     * reader needs, and decoding it whole is both slower and an easy way to run
     * an older device out of heap.
     *
     * BitmapFactory rather than ImageDecoder because the pixels have to be read
     * back, which rules out a hardware bitmap anyway — and this way there is one
     * path instead of one per Android version.
     */
    private fun readCode(uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight))
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        Qr.readImage(bitmap).also { bitmap.recycle() }
    }.getOrNull()

    /** The largest power of two that still leaves the picture readable. */
    private fun sampleSize(longestSide: Int): Int {
        var sample = 1
        while (longestSide / sample > MAX_PICTURE) sample *= 2
        return sample
    }

    /** The single action offered under a failure, carried out. */
    private fun perform(action: FailureAction, go: (Place) -> Unit) {
        when (action) {
            FailureAction.PasteKey, FailureAction.PasteAgain, FailureAction.RequestNewKey -> go(Place.KEY)
            FailureAction.Retry -> home.toggle(open = true)
            FailureAction.AskPermissionAgain -> container.tunnel.consentIntent()?.let(consent::launch)
            FailureAction.OpenNetworkSettings -> open(Settings.ACTION_WIRELESS_SETTINGS)
            FailureAction.OpenAirplaneSettings -> open(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            FailureAction.OpenCaptivePortal -> open(Settings.ACTION_WIFI_SETTINGS)
            FailureAction.WalkThroughBatterySettings -> open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            FailureAction.ShowOtherVpn -> open(Settings.ACTION_VPN_SETTINGS)
            // Everything that means "look closer" ends up in the same place:
            // the ladder, walked in front of the person, with the report at the
            // bottom of it.
            FailureAction.SendReport, FailureAction.ShowDetails -> go(Place.CHECK)
            FailureAction.OpenDownloads -> open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        }
    }

    private fun open(action: String) {
        runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { ZalesLog.warn(ZalesLog.TAG_UI, "no activity for $action", it) }
    }

    private companion object {
        /** Plenty for any code; far less than a modern screenshot. */
        const val MAX_PICTURE = 1_600
    }
}
