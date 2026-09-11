// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.design.switchboard

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Touch, in the hand.
 *
 * Half of what makes the switch feel like an object rather than a picture is
 * that it answers back: notches under the travelling blade, a heavy thud as the
 * latch goes over, and nothing at all when the forest is merely watching — the
 * thicket never touches the person (docs/DESIGN.md §9).
 *
 * Everything degrades quietly. The expressive primitives arrived in Android 12,
 * so older phones get a plain pulse and phones without a motor get silence.
 */
public class ZalesHaptics(context: Context) {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()?.takeIf { it.hasVibrator() }

    private val expressive: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vibrator?.let(::hasPrimitives) == true

    /** A fingertip landing on the handle. */
    public fun grasp(): Unit = tick(GRASP_SCALE, fallbackMs = 8)

    /** One notch of travel, as the blade passes a detent. */
    public fun notch(): Unit = tick(NOTCH_SCALE, fallbackMs = 6)

    /** The latch goes over: a rise into a heavy thud. */
    public fun latch() {
        if (expressive) playLatch() else legacy(28)
    }

    /** The path opened: two settling knocks. */
    public fun opened() {
        if (expressive) playSettle() else legacy(18)
    }

    /** The path narrowed. A warning, not an alarm. */
    public fun narrowed(): Unit = tick(NARROW_SCALE, fallbackMs = 12)

    /** It did not go through. Felt as "no", without scolding. */
    public fun refused() {
        if (expressive) playRefusal() else legacy(22)
    }

    private fun tick(scale: Float, fallbackMs: Long) {
        if (expressive) playTick(scale) else legacy(fallbackMs)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun hasPrimitives(target: Vibrator): Boolean = target.areAllPrimitivesSupported(
        VibrationEffect.Composition.PRIMITIVE_TICK,
        VibrationEffect.Composition.PRIMITIVE_THUD,
        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
    )

    @RequiresApi(Build.VERSION_CODES.S)
    private fun playTick(scale: Float) {
        play(
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale)
                .compose(),
        )
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun playLatch() {
        play(
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, LATCH_RISE)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1f)
                .compose(),
        )
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun playSettle() {
        play(
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, SETTLE_SCALE)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, SETTLE_SCALE, SETTLE_GAP_MS)
                .compose(),
        )
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun playRefusal() {
        play(
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN, REFUSE_SCALE)
                .compose(),
        )
    }

    private fun legacy(milliseconds: Long) {
        play(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun play(effect: VibrationEffect) {
        runCatching { vibrator?.vibrate(effect) }
    }

    private companion object {
        const val GRASP_SCALE = 0.3f
        const val NOTCH_SCALE = 0.22f
        const val LATCH_RISE = 0.6f
        const val SETTLE_SCALE = 0.5f
        const val SETTLE_GAP_MS = 90
        const val NARROW_SCALE = 0.4f
        const val REFUSE_SCALE = 0.4f
    }
}

@Composable
public fun rememberZalesHaptics(): ZalesHaptics {
    val context = LocalContext.current
    return remember(context) { ZalesHaptics(context) }
}
