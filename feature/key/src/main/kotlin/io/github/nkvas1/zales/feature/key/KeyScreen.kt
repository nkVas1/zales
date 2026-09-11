// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.feature.key

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.remember
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
    onScan: () -> Unit,
    onScanned: (String) -> Unit,
    onPickPicture: () -> Unit,
    onHandoff: (String) -> Unit,
    onCloseOverlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state.mode) {
        KeyMode.SCAN -> Overlay(modifier) { QrScanner(onFound = onScanned, onGiveUp = onCloseOverlay) }
        KeyMode.HANDOFF -> Overlay(modifier) { Handoff(state, onCloseOverlay) }
        KeyMode.TEXT -> Paperwork(
            state,
            onTextChange,
            onPasteClipboard,
            onSave,
            onForget,
            onScan,
            onPickPicture,
            onHandoff,
            modifier,
        )
    }
}

/** The two faces that take over the whole screen: the camera and the code. */
@Composable
private fun Overlay(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Zales.colors.void)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/**
 * This phone's key, drawn for another phone's camera.
 *
 * The whole point of the grandson-to-grandfather handover: nothing is typed,
 * nothing is read aloud over the telephone, nothing is sent through a messenger
 * that keeps a copy for ever.
 */
@Composable
private fun Handoff(state: KeyUiState, onClose: () -> Unit) {
    ZalesText(text = stringResource(R.string.key_handoff), style = Zales.type.title, color = Zales.colors.bone)
    val text = state.handoff
    if (text == null) {
        ZalesText(
            text = stringResource(R.string.key_handoff_too_big),
            style = Zales.type.body,
            color = Zales.colors.rust,
        )
    } else {
        ZalesText(
            text = stringResource(R.string.key_handoff_explain),
            style = Zales.type.body,
            color = Zales.colors.rime,
        )
        val grid = remember(text) { Qr.encode(text) }
        grid?.let { QrPlate(it) }
    }
    PlateButton(text = stringResource(R.string.key_handoff_close), onClick = onClose)
}

@Composable
private fun Paperwork(
    state: KeyUiState,
    onTextChange: (TextFieldValue) -> Unit,
    onPasteClipboard: () -> Unit,
    onSave: () -> Unit,
    onForget: (String) -> Unit,
    onScan: () -> Unit,
    onPickPicture: () -> Unit,
    onHandoff: (String) -> Unit,
    modifier: Modifier,
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

        // Offered before the field, because a key nearly always arrives as a
        // picture on someone else's screen or in a messenger, not as text
        // anybody would want to retype.
        PlateButton(text = stringResource(R.string.key_scan), onClick = onScan)
        PlateButton(text = stringResource(R.string.key_scan_picture), onClick = onPickPicture)

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
                StoredKeyRow(
                    key = key,
                    guarded = state.guarded,
                    onForget = { onForget(key.id) },
                    onHandoff = { onHandoff(key.id) },
                )
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
private fun StoredKeyRow(
    key: StoredKeyView,
    guarded: Boolean,
    onForget: () -> Unit,
    onHandoff: () -> Unit,
) {
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
        PlateButton(text = stringResource(R.string.key_handoff), onClick = onHandoff)
        if (!guarded) {
            PlateButton(text = stringResource(R.string.key_forget), onClick = onForget)
        }
    }
}

private val FIELD_SHAPE = RoundedCornerShape(3.dp)
