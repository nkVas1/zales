// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.design.thicket

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.nkvas1.zales.design.Zales
import io.github.nkvas1.zales.design.ZalesColors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The background of the whole app: a forest quantised to two tones.
 *
 * On Android 13 and later this is one AGSL pass. Below that there is no
 * `RuntimeShader`, so the same field is evaluated on the CPU at dither-cell
 * resolution and blitted with nearest-neighbour scaling — poorer, but the same
 * picture, and the app stays whole on an old phone
 * (docs/adr/0006-agsl-dithering.md).
 */
@Composable
public fun ThicketSurface(
    state: ThicketState,
    modifier: Modifier = Modifier,
    cell: Dp = DITHER_CELL,
) {
    val colors = Zales.colors
    val cellPx = with(LocalDensity.current) { cell.toPx() }.coerceAtLeast(1f)

    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.alive) {
        if (!state.alive) return@LaunchedEffect
        val started = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> time = (now - started) / NANOS_PER_SECOND }
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { RuntimeShader(THICKET_AGSL) }
        val brush = remember(shader) { ShaderBrush(shader) }
        Canvas(modifier) {
            shader.setFloatUniform("uResolution", size.width, size.height)
            shader.setFloatUniform("uTime", time)
            shader.setFloatUniform("uOpen", state.open)
            shader.setFloatUniform("uTilt", 0f, 0f)
            shader.setFloatUniform("uFlicker", lampFlicker(time))
            shader.setFloatUniform("uPulse", state.pulse)
            shader.setFloatUniform("uGaze", state.gaze)
            shader.setFloatUniform("uPixel", cellPx)
            shader.setColorUniform("uVoid", colors.void.toArgb())
            shader.setColorUniform("uCold", colors.cold.toArgb())
            shader.setColorUniform("uWarm", colors.lamp.toArgb())
            drawRect(brush)
        }
    } else {
        ThicketOnCpu(state, time, cellPx, colors, modifier)
    }
}

/**
 * The same field on the CPU, one pixel per dither cell, drawn as a single
 * bitmap scaled without smoothing.
 *
 * The field is only recomputed when the picture would actually change: how far
 * the path has opened, and a coarse step of the breathing. Everything between
 * is the same frame again, which is what keeps this affordable.
 */
@Composable
private fun ThicketOnCpu(
    state: ThicketState,
    time: Float,
    cellPx: Float,
    colors: ZalesColors,
    modifier: Modifier,
) {
    val openStep = (state.open * OPEN_STEPS).roundToInt()
    val breathStep = (time / SECONDS_PER_BREATH_STEP).toInt()
    val palette = remember(colors) { ZalesPalette(colors.void.toArgb(), colors.cold.toArgb(), colors.lamp.toArgb()) }
    val cache = remember { ThicketFieldCache() }

    Canvas(modifier) {
        val columns = (size.width / cellPx).toInt().coerceAtLeast(1)
        val rows = (size.height / cellPx).toInt().coerceAtLeast(1)
        val field = cache.get(columns, rows, openStep, breathStep, palette)
        drawImage(
            image = field,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            dstOffset = IntOffset.Zero,
            filterQuality = FilterQuality.None,
        )
    }
}

/** Holds the last rendered field so identical frames are not recomputed. */
private class ThicketFieldCache {
    private var key: String = ""
    private var image: ImageBitmap? = null

    fun get(columns: Int, rows: Int, openStep: Int, breathStep: Int, colors: ZalesPalette): ImageBitmap {
        val id = "$columns:$rows:$openStep:$breathStep"
        image?.let { if (id == key) return it }
        val rendered =
            render(columns, rows, openStep / OPEN_STEPS.toFloat(), breathStep * SECONDS_PER_BREATH_STEP, colors)
        key = id
        image = rendered
        return rendered
    }

    private fun render(columns: Int, rows: Int, open: Float, time: Float, colors: ZalesPalette): ImageBitmap {
        val pixels = IntArray(columns * rows)
        val breath = 0.5f * sin(time * 0.21f) + 0.5f * sin(time * 0.13f + 1.7f)
        val flicker = lampFlicker(time)
        val voidArgb = colors.voidArgb
        val coldArgb = colors.coldArgb
        val warmArgb = colors.warmArgb

        for (row in 0 until rows) {
            val v = row.toFloat() / rows
            for (column in 0 until columns) {
                val u = column.toFloat() / columns
                val lamp = lampAt(u, v) * flicker
                val density = densityAt(u, v, open, breath)
                val value = density * 0.85f + lamp * 0.95f
                val threshold = bayer8(column, row) * 0.92f + 0.04f
                pixels[row * columns + column] = when {
                    value <= threshold -> voidArgb
                    lamp * 1.4f > density -> warmArgb
                    else -> coldArgb
                }
            }
        }
        return Bitmap.createBitmap(pixels, columns, rows, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

/** The three colours the field needs, resolved once so the loop stays arithmetic. */
private class ZalesPalette(val voidArgb: Int, val coldArgb: Int, val warmArgb: Int)

private fun densityAt(u: Float, v: Float, open: Float, breath: Float): Float {
    val forestY = 1f - v
    val far = belt(u, forestY, 26f, 3.1f, 0.30f, 0.010f * breath) * 0.34f
    val mid = belt(u, forestY, 15f, 9.7f, 0.46f, 0.018f * breath) * 0.62f
    val near = belt(u, forestY, 8f, 17.3f, 0.72f, 0.026f * breath)
    var density = maxOf(far, mid, near)
    density = maxOf(density, smoothstep(0.28f, 0f, forestY) * 0.55f)
    val halfWidth = open * 0.30f
    return density * smoothstep(halfWidth, halfWidth + 0.10f, abs(u - 0.5f))
}

private fun belt(u: Float, forestY: Float, scale: Float, seed: Float, tall: Float, sway: Float): Float {
    val x = u * scale + seed
    val jitter = hash1(floor(x) * 1.37f + seed)
    val f = fract(fract(x) + sway * (jitter - 0.5f))
    val crown = (0.42f + 0.58f * jitter) * tall
    val profile = crown * (1f - abs(f - 0.5f) * 2f * 0.78f)
    return smoothstep(profile + 0.012f, profile - 0.012f, forestY)
}

private fun lampAt(u: Float, v: Float): Float {
    val dx = u - 0.26f
    val dy = v - 0.30f
    val reach = sqrt(dx * dx + dy * dy) / 0.46f
    return exp(-reach * reach * 2.2f)
}

private fun bayer8(x: Int, y: Int): Float = bayer4(x / 2, y / 2) * 0.25f + bayer2(x, y)
private fun bayer4(x: Int, y: Int): Float = bayer2(x / 2, y / 2) * 0.25f + bayer2(x, y)
private fun bayer2(x: Int, y: Int): Float = fract(x / 2f + (y * y) * 0.75f)

private fun hash1(n: Float): Float = fract(sin(n) * 43758.5453123f)
private fun fract(value: Float): Float = value - floor(value)

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    if (edge0 == edge1) return if (x < edge0) 0f else 1f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * A filament on sagging mains: never quite steady, never obviously flickering.
 * Two slow waves plus a rare dip, all kept inside three percent.
 */
internal fun lampFlicker(time: Float): Float {
    val slow = sin(time * 2.3f) * 0.4f + sin(time * 5.1f + 1.1f) * 0.3f
    val dip = if (hash1(floor(time * 1.7f)) > 0.94f) -0.6f else 0f
    return (1f + (slow + dip) * 0.03f).coerceIn(0.94f, 1f)
}

/** Dither cells are chunky on purpose: the grain is the material. */
private val DITHER_CELL = 3.dp
private const val NANOS_PER_SECOND = 1_000_000_000f
private const val OPEN_STEPS = 24
private const val SECONDS_PER_BREATH_STEP = 0.25f
