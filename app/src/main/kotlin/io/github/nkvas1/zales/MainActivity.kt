// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nkvas1.zales.common.ZalesLog
import io.github.nkvas1.zales.design.ZalesTheme
import io.github.nkvas1.zales.feature.home.HomeEvent
import io.github.nkvas1.zales.feature.home.HomeScreen
import io.github.nkvas1.zales.feature.home.HomeViewModel
import io.github.nkvas1.zales.feature.key.AndroidClipboard
import io.github.nkvas1.zales.feature.key.KeyScreen
import io.github.nkvas1.zales.feature.key.KeyViewModel
import io.github.nkvas1.zales.words.FailureAction

/**
 * The single window. Two places to be: the switch, and the key.
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
                var showKeys by remember { mutableStateOf(false) }
                val homeState by home.state.collectAsStateWithLifecycle()
                val keyState by key.state.collectAsStateWithLifecycle()
                val event by home.event.collectAsStateWithLifecycle()

                LaunchedEffect(event) {
                    when (event) {
                        HomeEvent.AskVpnConsent -> {
                            container.tunnel.consentIntent()?.let(consent::launch) ?: home.onConsentGranted()
                        }
                        HomeEvent.OpenKeys -> {
                            showKeys = true
                            home.onEventHandled()
                        }
                        null -> Unit
                    }
                }

                if (showKeys) {
                    KeyScreen(
                        state = keyState,
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
                        modifier = Modifier,
                    )
                } else {
                    HomeScreen(
                        state = homeState,
                        onToggle = home::toggle,
                        onAction = { action -> perform(action) { showKeys = true } },
                        onHint = home::hint,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        home.refreshKeys()
        key.refresh()
    }

    /** The single action offered under a failure, carried out. */
    private fun perform(action: FailureAction, openKeys: () -> Unit) {
        when (action) {
            FailureAction.PasteKey, FailureAction.PasteAgain -> openKeys()
            FailureAction.Retry -> home.toggle(open = true)
            FailureAction.AskPermissionAgain -> container.tunnel.consentIntent()?.let(consent::launch)
            FailureAction.OpenNetworkSettings -> open(Settings.ACTION_WIRELESS_SETTINGS)
            FailureAction.OpenAirplaneSettings -> open(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            FailureAction.OpenCaptivePortal -> open(Settings.ACTION_WIFI_SETTINGS)
            FailureAction.WalkThroughBatterySettings -> open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            FailureAction.ShowOtherVpn -> open(Settings.ACTION_VPN_SETTINGS)
            // These become their own screens in the diagnostics phase; until then
            // the honest thing is to send the person where they can act.
            FailureAction.SendReport, FailureAction.ShowDetails, FailureAction.RequestNewKey -> openKeys()
            FailureAction.OpenDownloads -> open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        }
    }

    private fun open(action: String) {
        runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { ZalesLog.warn(ZalesLog.TAG_UI, "no activity for $action", it) }
    }
}
