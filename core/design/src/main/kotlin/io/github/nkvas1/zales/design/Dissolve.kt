// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.design

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * How one screen becomes another here: it dissolves, pixel by pixel, through an
 * ordered dither.
 *
 * Never a fade. A fade is a sheet of glass sliding over a photograph, and this
 * app has no glass in it anywhere (CLAUDE.md rule 1). What happens instead is
 * what happens to an old print left in the damp: it goes in grains, in a fixed
 * pattern, and the pattern is the same Bayer matrix the forest is drawn with.
 *
 * Below Android 13 there is no `RuntimeShader`, and rather than substitute the
 * fade this exists to avoid, the screen simply changes at the halfway point.
 * Honest, and over in a blink.
 */
@Composable
public fun <T> DitherDissolve(
    target: T,
    modifier: Modifier = Modifier,
    durationMs: Int = DISSOLVE_MS,
    content: @Composable (T) -> Unit,
) {
    var shown by remember { mutableStateOf(target) }
    var leaving by remember { mutableStateOf<T?>(null) }

    // Someone who asked the system to stop moving things is asking for this too.
    val instant = Motion.stilled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    if (target != shown) {
        leaving = shown.takeIf { !instant }
        shown = target
    }

    // How much of the departing screen is still there. Driven by hand rather
    // than by a state animation so that a second change part-way through starts
    // over cleanly instead of continuing from wherever the first one got to.
    val remaining = remember { Animatable(0f) }
    LaunchedEffect(leaving) {
        if (leaving == null) return@LaunchedEffect
        remaining.snapTo(1f)
        remaining.animateTo(0f, tween(durationMillis = durationMs))
        leaving = null
    }

    Box(modifier) {
        content(shown)
        val departing = leaving
        if (departing != null && !instant) {
            Box(Modifier.fillMaxSize().dissolving(remaining.value)) { content(departing) }
        }
    }
}

/**
 * Eats the layer away in dither cells.
 *
 * The cell is fixed in device pixels rather than in dp, so the grain is the
 * same size as the forest's and the two never disagree about how coarse the
 * world is.
 */
@Composable
private fun Modifier.dissolving(remaining: Float): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    val cell = with(LocalDensity.current) { DITHER_CELL.toPx() }
    val shader = remember { RuntimeShader(DISSOLVE_AGSL) }
    return graphicsLayer {
        shader.setFloatUniform("uRemaining", remaining)
        shader.setFloatUniform("uPixel", cell)
        renderEffect = RenderEffect
            .createRuntimeShaderEffect(shader, "uContent")
            .asComposeRenderEffect()
        clip = true
    }
}

private const val DISSOLVE_MS = 260
private val DITHER_CELL = 2.dp

/**
 * The same recursive 8×8 Bayer construction the thicket uses, applied as a
 * threshold on alpha rather than on brightness.
 */
private const val DISSOLVE_AGSL = """
uniform shader uContent;
uniform float uRemaining;
uniform float uPixel;

float bayer2(float2 a) {
    a = floor(a);
    return fract(a.x / 2.0 + a.y * a.y * 0.75);
}

float bayer4(float2 a) {
    return bayer2(0.5 * a) * 0.25 + bayer2(a);
}

float bayer8(float2 a) {
    return bayer4(0.5 * a) * 0.25 + bayer2(a);
}

half4 main(float2 coord) {
    half4 source = uContent.eval(coord);
    float threshold = bayer8(coord / max(uPixel, 1.0));
    // Kept whole until its own cell's turn comes, then gone at once. No cell is
    // ever half there, which is what makes this read as grain and not as fog.
    return threshold < uRemaining ? source : half4(0.0);
}
"""
