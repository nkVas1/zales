// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.design.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.nkvas1.zales.design.R
import io.github.nkvas1.zales.design.Zales
import io.github.nkvas1.zales.design.switchboard.rememberZalesHaptics

/**
 * A setting, as a small brass slide-bolt on a plate.
 *
 * Not a Material switch: no pill, no ripple, no colour-filled track. The bolt
 * sits at one end of a slot and slides to the other, and the whole row is the
 * target — 56dp tall at least, because an unsteady hand should not have to hit
 * a thumb-sized toggle at the edge of the screen.
 */
@Composable
public fun PlateToggle(
    title: String,
    explanation: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberZalesHaptics()
    val on = stringResource(R.string.a11y_toggle_on)
    val off = stringResource(R.string.a11y_toggle_off)
    val interaction = remember { MutableInteractionSource() }
    val slide by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 420f),
        label = "bolt",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MIN_TOUCH_TARGET)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
            ) {
                haptics.notch()
                onChange(!checked)
            }
            .semantics {
                stateDescription = if (checked) on else off
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ZalesText(text = title, style = Zales.type.body, color = Zales.colors.bone)
            ZalesText(text = explanation, style = Zales.type.caption, color = Zales.colors.rime)
        }
        Spacer(Modifier.width(16.dp))
        Bolt(slide, checked)
    }
}

@Composable
private fun Bolt(slide: Float, checked: Boolean) {
    val slot = Zales.colors.deep
    val edge = Zales.colors.cold
    val metal = if (checked) Zales.colors.brass else Zales.colors.breath

    Box(
        modifier = Modifier
            .size(width = TRACK_WIDTH, height = TRACK_HEIGHT)
            .background(slot, SHAPE)
            .border(1.dp, edge, SHAPE),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.size(width = TRACK_WIDTH, height = TRACK_HEIGHT)) {
            val boltWidth = size.width * BOLT_FRACTION
            val travel = size.width - boltWidth - INSET * 2
            drawRect(
                color = metal,
                topLeft = Offset(INSET + travel * slide, INSET),
                size = Size(boltWidth, size.height - INSET * 2),
            )
        }
    }
}

private val SHAPE = RoundedCornerShape(2.dp)
private val TRACK_WIDTH = 58.dp
private val TRACK_HEIGHT = 30.dp
private val MIN_TOUCH_TARGET = 56.dp
private const val BOLT_FRACTION = 0.46f
private const val INSET = 4f
