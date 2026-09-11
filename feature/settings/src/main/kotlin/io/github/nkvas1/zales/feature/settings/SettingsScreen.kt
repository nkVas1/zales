// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nkvas1.zales.design.Zales
import io.github.nkvas1.zales.design.component.PlateButton
import io.github.nkvas1.zales.design.component.PlateToggle
import io.github.nkvas1.zales.design.component.ZalesText
import io.github.nkvas1.zales.settings.Preferences

/**
 * Everything a person can change, which is deliberately almost nothing.
 *
 * Each row is a sentence somebody might actually say out loud — "не показывай
 * мне эти присказки", "банк меня не пускает" — and not a feature with a name.
 * A setting nobody would think to ask for is one more thing to get lost in.
 */
@Composable
public fun SettingsScreen(
    preferences: Preferences,
    update: UpdateState,
    version: String,
    onChange: (Preferences) -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenDownloads: (String) -> Unit,
    onAlwaysOn: () -> Unit,
    onCheckPath: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Zales.colors.void)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZalesText(text = stringResource(R.string.settings_title), style = Zales.type.title, color = Zales.colors.bone)

        PlateToggle(
            title = stringResource(R.string.settings_only_switch),
            explanation = stringResource(R.string.settings_only_switch_explain),
            checked = preferences.onlyTheSwitch,
            onChange = { onChange(preferences.copy(onlyTheSwitch = it)) },
        )
        PlateToggle(
            title = stringResource(R.string.settings_sayings),
            explanation = stringResource(R.string.settings_sayings_explain),
            checked = preferences.sayings,
            onChange = { onChange(preferences.copy(sayings = it)) },
        )
        PlateToggle(
            title = stringResource(R.string.settings_bypass),
            explanation = stringResource(R.string.settings_bypass_explain),
            checked = preferences.bypassDomestic,
            onChange = { onChange(preferences.copy(bypassDomestic = it)) },
        )
        PlateToggle(
            title = stringResource(R.string.settings_updates),
            explanation = stringResource(R.string.settings_updates_explain),
            checked = preferences.checkForUpdates,
            onChange = { onChange(preferences.copy(checkForUpdates = it)) },
        )

        Spacer(Modifier.height(8.dp))
        AlwaysOn(onAlwaysOn)
        PathCheck(onCheckPath)
        Updates(update, onCheckUpdate, onOpenDownloads)

        Spacer(Modifier.height(12.dp))
        ZalesText(
            text = stringResource(R.string.settings_version, version),
            style = Zales.type.nameplate,
            color = Zales.colors.rime,
        )
        ZalesText(
            text = stringResource(R.string.settings_source),
            style = Zales.type.nameplate,
            color = Zales.colors.rime,
        )

        Spacer(Modifier.height(8.dp))
        PlateButton(text = stringResource(R.string.settings_back), onClick = onLeave)
    }
}

/**
 * The one setting worth more than all the others put together, and the one
 * nobody finds on their own: Android's own always-on VPN with connections
 * blocked without it.
 */
@Composable
private fun AlwaysOn(onOpen: () -> Unit) {
    Section(
        title = stringResource(R.string.settings_always_on),
        explanation = stringResource(R.string.settings_always_on_explain),
    ) {
        PlateButton(text = stringResource(R.string.settings_always_on_go), onClick = onOpen)
    }
}

@Composable
private fun PathCheck(onCheck: () -> Unit) {
    Section(
        title = stringResource(R.string.settings_check_path),
        explanation = stringResource(R.string.settings_check_path_explain),
    ) {
        PlateButton(text = stringResource(R.string.settings_check_path), onClick = onCheck)
    }
}

@Composable
private fun Updates(state: UpdateState, onCheck: () -> Unit, onOpen: (String) -> Unit) {
    Section(
        title = stringResource(R.string.settings_updates),
        explanation = when (state) {
            UpdateState.Looking -> stringResource(R.string.settings_update_looking)
            UpdateState.UpToDate -> stringResource(R.string.settings_update_current)
            UpdateState.Unreachable -> stringResource(R.string.settings_update_unreachable)
            is UpdateState.Newer -> stringResource(R.string.settings_update_newer, state.version)
            UpdateState.Unknown -> stringResource(R.string.settings_updates_explain)
        },
    ) {
        if (state is UpdateState.Newer) {
            PlateButton(text = stringResource(R.string.settings_update_open), onClick = { onOpen(state.page) })
        } else {
            PlateButton(
                text = stringResource(R.string.settings_update_look),
                onClick = onCheck,
                enabled = state != UpdateState.Looking,
            )
        }
    }
}

@Composable
private fun Section(title: String, explanation: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZalesText(text = title, style = Zales.type.body, color = Zales.colors.bone)
        ZalesText(text = explanation, style = Zales.type.caption, color = Zales.colors.rime)
        content()
    }
}
