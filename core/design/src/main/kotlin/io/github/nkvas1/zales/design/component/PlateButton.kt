// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.design.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.nkvas1.zales.design.Zales
import io.github.nkvas1.zales.design.switchboard.rememberZalesHaptics

/**
 * The only button in the app: an engraved plate screwed to the panel.
 *
 * Deliberately unlike a Material button — no ripple, no elevation, no rounded
 * pill. It presses in, it is at least 56dp tall so that an unsteady hand finds
 * it, and it answers with a tick.
 *
 * Plates in a column are all the same width. Sized to their own text they come
 * out ragged, which reads as unfinished rather than as deliberate — and the
 * wider target is the friendlier one for the hand this was built for. A button
 * standing on its own still takes only the room it needs.
 */
@Composable
public fun PlateButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = rememberZalesHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "press",
    )

    val brass = Zales.colors.brass
    val face = Zales.colors.carbolite

    Box(
        modifier = modifier
            .scale(scale)
            .defaultMinSize(minHeight = MIN_TOUCH_TARGET)
            .background(if (enabled) face else face.copy(alpha = DISABLED_ALPHA), SHAPE)
            .border(BORDER, if (enabled) brass else brass.copy(alpha = DISABLED_ALPHA), SHAPE)
            .then(
                if (enabled) {
                    Modifier.clickableWithoutRipple(interaction, Role.Button) {
                        haptics.notch()
                        onClick()
                    }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        ZalesText(
            text = text,
            style = Zales.type.body,
            color = if (enabled) Zales.colors.bone else Zales.colors.rime,
        )
    }
}

private fun Modifier.clickableWithoutRipple(
    interaction: MutableInteractionSource,
    role: Role,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = interaction,
    indication = null,
    role = role,
    onClick = onClick,
)

private val SHAPE = RoundedCornerShape(3.dp)
private val BORDER = 1.5.dp
private val MIN_TOUCH_TARGET = 56.dp
private const val PRESSED_SCALE = 0.975f
private const val DISABLED_ALPHA = 0.4f
