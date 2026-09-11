// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.feature.key

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.nkvas1.zales.design.Zales
import io.github.nkvas1.zales.design.component.PlateButton
import io.github.nkvas1.zales.design.component.ZalesText

/**
 * Where a key comes in.
 *
 * One field, one button, and an offer to use what is already on the clipboard —
 * because that is where a key nearly always is when someone has just been sent
 * one. Nothing has to be typed, and nothing has to be understood.
 */
@Composable
public fun KeyScreen(
    state: KeyUiState,
    onTextChange: (TextFieldValue) -> Unit,
    onPasteClipboard: () -> Unit,
    onSave: () -> Unit,
    onForget: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Zales.colors.void)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ZalesText(text = stringResource(R.string.key_title), style = Zales.type.title, color = Zales.colors.bone)
        ZalesText(
            text = stringResource(R.string.key_explanation),
            style = Zales.type.body,
            color = Zales.colors.rime,
        )

        ClipboardOffer(offered = state.clipboardLooksLikeKey, onPaste = onPasteClipboard)

        BasicTextField(
            value = state.text,
            onValueChange = onTextChange,
            textStyle = Zales.type.body.copy(color = Zales.colors.bone),
            cursorBrush = SolidColor(Zales.colors.ember),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp)
                .background(Zales.colors.deep, FIELD_SHAPE)
                .border(1.dp, Zales.colors.cold, FIELD_SHAPE)
                .padding(16.dp),
        )

        SaveOutcome(state)

        PlateButton(
            text = stringResource(R.string.key_save),
            onClick = onSave,
            enabled = state.text.text.isNotBlank() && !state.busy,
        )

        if (state.stored.isNotEmpty()) {
            Spacer(Modifier.heightIn(min = 8.dp))
            ZalesText(text = stringResource(R.string.key_stored), style = Zales.type.caption, color = Zales.colors.rime)
            state.stored.forEach { key ->
                StoredKeyRow(key = key, onForget = { onForget(key.id) })
            }
        }
    }
}

/** Only shown when there really is something worth offering to paste. */
@Composable
private fun ClipboardOffer(offered: Boolean, onPaste: () -> Unit) {
    if (!offered) return
    ZalesText(
        text = stringResource(R.string.key_clipboard_noticed),
        style = Zales.type.caption,
        color = Zales.colors.lamp,
    )
    PlateButton(text = stringResource(R.string.key_paste_clipboard), onClick = onPaste)
}

/** What the last save did, said plainly. */
@Composable
private fun SaveOutcome(state: KeyUiState) {
    state.problem?.let { problem ->
        ZalesText(text = stringResource(problem), style = Zales.type.body, color = Zales.colors.rust)
    }
    state.acceptedCount?.let { count ->
        val message = if (count > 1) {
            stringResource(R.string.key_accepted_many, count)
        } else {
            stringResource(R.string.key_accepted)
        }
        ZalesText(text = message, style = Zales.type.body, color = Zales.colors.lamp)
    }
}

@Composable
private fun StoredKeyRow(key: StoredKeyView, onForget: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Zales.colors.deep, FIELD_SHAPE)
            .border(1.dp, if (key.active) Zales.colors.brass else Zales.colors.cold, FIELD_SHAPE)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZalesText(text = key.label, style = Zales.type.body, color = Zales.colors.bone)
        ZalesText(text = key.detail, style = Zales.type.nameplate, color = Zales.colors.rime)
        if (key.insecure) {
            ZalesText(
                text = stringResource(R.string.key_insecure),
                style = Zales.type.caption,
                color = Zales.colors.rust,
            )
        }
        PlateButton(text = stringResource(R.string.key_forget), onClick = onForget)
    }
}

private val FIELD_SHAPE = RoundedCornerShape(3.dp)
