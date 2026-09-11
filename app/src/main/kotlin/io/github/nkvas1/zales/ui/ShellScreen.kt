// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.nkvas1.zales.design.Zales
import io.github.nkvas1.zales.design.ZalesTheme
import io.github.nkvas1.zales.design.component.ZalesText

/**
 * Phase 0 shell.
 *
 * Deliberately not the real home screen — the thicket, the lamp and the knife
 * switch arrive in phase 3. What this screen does prove is that the palette,
 * the type scale and edge-to-edge layout are wired end to end on a real device.
 */
@Composable
fun ShellScreen(
    versionName: String,
    versionCode: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Zales.colors.void)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        ZalesText(
            text = "$versionName · $versionCode",
            style = Zales.type.nameplate,
            color = Zales.colors.rime,
            modifier = Modifier.align(Alignment.TopEnd),
        )

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ZalesText(
                text = "ZALES",
                style = Zales.type.state,
                color = Zales.colors.bone,
                align = TextAlign.Center,
            )
            ZalesText(
                text = "фаза 0 · основание",
                style = Zales.type.caption,
                color = Zales.colors.rime,
                align = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        ZalesText(
            text = "здесь будет чаща",
            style = Zales.type.caption,
            color = Zales.colors.rime,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ShellScreenPreview() {
    ZalesTheme {
        ShellScreen(versionName = "0.1.0", versionCode = 1)
    }
}
