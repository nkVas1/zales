// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.design.switchboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * The moving half of the switch, simulated rather than animated.
 *
 * Travel runs 0 at the closed blade, carrying current, to 1 at the blade hanging
 * fully open. Gravity always pulls towards 1, a spring pulls towards wherever
 * the tunnel actually is, and the finger overrides both while it is down.
 */
@Stable
internal class Blade(initialTravel: Float, private val haptics: ZalesHaptics) {

    var travel by mutableFloatStateOf(initialTravel)
        private set

    var held by mutableStateOf(false)
        private set

    private var velocity = 0f
    private var lastNotch = initialTravel

    fun moveTo(next: Float) {
        val clamped = next.coerceIn(0f, 1f)
        if (abs(clamped - lastNotch) >= NOTCH_SPACING) {
            lastNotch = clamped
            haptics.notch()
        }
        travel = clamped
    }

    fun grab() {
        held = true
        velocity = 0f
        haptics.grasp()
    }

    /** Returns true when the handle was carried far enough to count as thrown. */
    fun release(): Boolean {
        held = false
        return travel < LATCH_TRAVEL
    }

    fun letGo() {
        held = false
    }

    /** Runs until the composition leaves, taking over whenever no finger is down. */
    suspend fun simulate(closed: () -> Boolean) {
        var previous = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - previous) / NANOS_PER_SECOND).coerceAtMost(MAX_STEP)
            previous = now
            if (held) continue

            val target = if (closed()) 0f else 1f
            val accel = (target - travel) * STIFFNESS - velocity * DAMPING + GRAVITY * (1f - target)
            velocity += accel * dt
            val next = travel + velocity * dt
            if (next <= 0f || next >= 1f) velocity = 0f
            moveTo(next)
        }
    }

    /** Carries the handle over for someone who held it rather than dragged it. */
    suspend fun driveOver(close: Boolean) {
        held = true
        val from = if (close) 1f else 0f
        val to = if (close) 0f else 1f
        repeat(DRIVE_STEPS) { step ->
            moveTo(from + (to - from) * easeOutBack((step + 1f) / DRIVE_STEPS))
            delay(DRIVE_STEP_MS)
        }
        held = false
    }

    /**
     * A touch that was not a throw: the handle stirs a few millimetres and
     * falls back. It is the answer to "did it hear me?" — and it is the reason
     * the written hint underneath is believed rather than argued with.
     */
    suspend fun shudder() {
        val from = travel
        val towards = if (from > HALF) from - SHUDDER_TRAVEL else from + SHUDDER_TRAVEL
        held = true
        repeat(SHUDDER_STEPS) { step ->
            val phase = (step + 1f) / SHUDDER_STEPS
            // Out and back within one short breath, without a notch tick.
            travel = (from + (towards - from) * kotlin.math.sin(phase * Math.PI.toFloat())).coerceIn(0f, 1f)
            delay(SHUDDER_STEP_MS)
        }
        travel = from
        lastNotch = from
        held = false
    }

    /** A little overshoot at the end, the way a real handle settles into its stop. */
    private fun easeOutBack(t: Float): Float {
        val overshoot = 1.70158f
        val p = t - 1f
        return 1f + (overshoot + 1f) * p * p * p + overshoot * p * p
    }

    companion object {
        /** How far the handle must travel before the throw counts. */
        const val LATCH_TRAVEL = 0.28f
        const val CLOSED_ENOUGH = 0.04f
        const val THROW_TRAVEL_FRACTION = 0.55f
        const val HOLD_TO_THROW_MS = 400L

        private const val NOTCH_SPACING = 0.12f
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val MAX_STEP = 1f / 20f
        private const val STIFFNESS = 380f
        private const val DAMPING = 26f
        private const val GRAVITY = 2.4f
        private const val DRIVE_STEPS = 22
        private const val DRIVE_STEP_MS = 16L
        private const val HALF = 0.5f
        private const val SHUDDER_TRAVEL = 0.045f
        private const val SHUDDER_STEPS = 12
        private const val SHUDDER_STEP_MS = 16L
    }
}

@Composable
internal fun rememberBlade(closed: Boolean, haptics: ZalesHaptics): Blade {
    val blade = remember(haptics) { Blade(if (closed) 0f else 1f, haptics) }
    val current by rememberUpdatedState(closed)
    LaunchedEffect(blade) {
        while (isActive) {
            blade.simulate { current }
        }
    }
    return blade
}
