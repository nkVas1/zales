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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    keyInUse: String?,
    keyCount: Int,
    onChange: (Preferences) -> Unit,
    onCheckUpdate: () -> Unit,
    onBrowse: (String) -> Unit,
    onAlwaysOn: () -> Unit,
    onCheckPath: () -> Unit,
    onOpenKeys: () -> Unit,
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

        Keys(keyInUse, keyCount, onOpenKeys)
        Spacer(Modifier.height(8.dp))

        Switches(preferences, onChange)

        Spacer(Modifier.height(8.dp))
        Stuck(onCheckPath)
        AlwaysOn(onAlwaysOn)
        PathCheck(onCheckPath)
        Updates(update, onCheckUpdate, onBrowse)
        Privacy(onBrowse)

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
        PlateButton(
            text = stringResource(R.string.settings_back),
            onClick = onLeave,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The key, and the only way back to it.
 *
 * Everything else on this screen is something a person might change one day.
 * This is the one row somebody opens settings *for* — a new key arrived, or the
 * old one stopped working — so it stands above the toggles rather than among
 * them. The name of the key in use is on the row itself, because "which key am
 * I on" is the question being asked, and answering it should not need a tap.
 */
@Composable
private fun Keys(inUse: String?, count: Int, onOpen: () -> Unit) {
    Section(
        title = stringResource(R.string.settings_keys),
        explanation = when {
            inUse == null -> stringResource(R.string.settings_keys_none)
            count > 1 -> stringResource(R.string.settings_keys_several, inUse)
            else -> stringResource(R.string.settings_keys_one, inUse)
        },
    ) {
        PlateButton(
            text = stringResource(if (inUse == null) R.string.settings_keys_paste else R.string.settings_keys_open),
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The four things a person can turn on and off, and nothing else. */
@Composable
private fun Switches(preferences: Preferences, onChange: (Preferences) -> Unit) {
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
}

/**
 * The one setting worth more than all the others put together, and the one
 * nobody finds on their own: Android's own always-on VPN with connections
 * blocked without it.
 *
 * It cannot be turned on from here — no app is allowed to — and there is no API
 * to read back whether it took, so the honest thing is to say exactly which
 * four taps to make and then get out of the way. Naming the Samsung wording
 * alongside the stock one is not padding: the two differ, and being sent to a
 * screen that says something else is where people give up.
 */
@Composable
private fun AlwaysOn(onOpen: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Section(
        title = stringResource(R.string.settings_always_on),
        explanation = stringResource(R.string.settings_always_on_explain),
    ) {
        PlateButton(
            text = stringResource(if (open) R.string.settings_always_on_hide else R.string.settings_always_on_show),
            onClick = { open = !open },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!open) return@Section

        listOf(
            R.string.settings_always_on_step_1,
            R.string.settings_always_on_step_2,
            R.string.settings_always_on_step_3,
            R.string.settings_always_on_step_4,
        ).forEach { step ->
            ZalesText(text = stringResource(step), style = Zales.type.body, color = Zales.colors.bone)
        }
        ZalesText(
            text = stringResource(R.string.settings_always_on_warning),
            style = Zales.type.caption,
            color = Zales.colors.rust,
        )
        PlateButton(
            text = stringResource(R.string.settings_always_on_go),
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * For someone whose tunnel is down and who is already upset.
 *
 * Three steps in order, no branches and no choices, and it opens by saying that
 * nothing irreversible has happened — which is the first thing a person in that
 * state needs to hear and the last thing most software thinks to say.
 */
@Composable
private fun Stuck(onCheckPath: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Section(
        title = stringResource(R.string.settings_stuck),
        explanation = stringResource(R.string.settings_stuck_explain),
    ) {
        PlateButton(
            text = stringResource(if (open) R.string.settings_stuck_hide else R.string.settings_stuck_show),
            onClick = { open = !open },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!open) return@Section

        ZalesText(
            text = stringResource(R.string.settings_stuck_calm),
            style = Zales.type.body,
            color = Zales.colors.lamp,
        )
        listOf(
            R.string.settings_stuck_1,
            R.string.settings_stuck_2,
            R.string.settings_stuck_3,
        ).forEach { step ->
            ZalesText(text = stringResource(step), style = Zales.type.body, color = Zales.colors.bone)
        }
        PlateButton(
            text = stringResource(R.string.settings_check_path),
            onClick = onCheckPath,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A VPN that says it collects nothing has to make that checkable, or it is just
 * another promise. The page it opens cites the file behind every claim.
 */
@Composable
private fun Privacy(onBrowse: (String) -> Unit) {
    Section(
        title = stringResource(R.string.settings_privacy),
        explanation = stringResource(R.string.settings_privacy_explain),
    ) {
        PlateButton(
            text = stringResource(R.string.settings_privacy_open),
            onClick = { onBrowse(PRIVACY_URL) },
        )
    }
}

@Composable
private fun PathCheck(onCheck: () -> Unit) {
    Section(
        title = stringResource(R.string.settings_check_path),
        explanation = stringResource(R.string.settings_check_path_explain),
    ) {
        PlateButton(
            text = stringResource(R.string.settings_check_path),
            onClick = onCheck,
            modifier = Modifier.fillMaxWidth(),
        )
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
            PlateButton(
                text = stringResource(R.string.settings_update_open),
                onClick = { onOpen(state.page) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            PlateButton(
                text = stringResource(R.string.settings_update_look),
                onClick = onCheck,
                enabled = state != UpdateState.Looking,
                modifier = Modifier.fillMaxWidth(),
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

private const val PRIVACY_URL = "https://github.com/nkVas1/zales/blob/main/docs/PRIVACY.md"
