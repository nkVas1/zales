// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.feature.key

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.nkvas1.zales.design.Zales
import kotlin.math.floor

/**
 * The key, drawn as something to point a camera at.
 *
 * Rendered module by module rather than as a scaled image: at this size a
 * filtered bitmap goes soft at the edges, and soft edges are exactly what makes
 * a camera give up. Hard squares, snapped to whole pixels.
 *
 * The code is light on dark, which is the way round a phone camera prefers when
 * the source is a glowing screen rather than paper.
 */
@Composable
internal fun QrPlate(grid: QrGrid, modifier: Modifier = Modifier) {
    val ground = Zales.colors.deep
    val module = Zales.colors.bone
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            // The reader needs the quiet zone; the eye reads it as a mount.
            .padding(QUIET_ZONE)
            // A picture of a key says nothing useful out loud.
            .clearAndSetSemantics { },
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            drawRect(color = ground, topLeft = Offset.Zero, size = size)
            drawModules(grid, module)
        }
    }
}

private fun DrawScope.drawModules(grid: QrGrid, colour: androidx.compose.ui.graphics.Color) {
    val side = minOf(size.width, size.height)
    val step = side / grid.size
    // Whole pixels, or neighbouring modules leave hairlines between them.
    val cell = Size(floor(step) + 1f, floor(step) + 1f)
    for (y in 0 until grid.size) {
        for (x in 0 until grid.size) {
            if (!grid.isDark(x, y)) continue
            drawRect(
                color = colour,
                topLeft = Offset(floor(x * step), floor(y * step)),
                size = cell,
            )
        }
    }
}

private val QUIET_ZONE = 16.dp
