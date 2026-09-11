// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.design.switchboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * The knife switch: the only control in Zales, and the only object in it that
 * is rendered rather than drawn.
 *
 * Two ways in, both first-class: drag the handle, or simply hold a finger on it
 * and let the switch throw itself — the way that needs no aim at all.
 */
@Composable
public fun KnifeSwitch(
    closed: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onHint: () -> Unit = {},
    energised: Float = 0f,
) {
    val haptics = rememberZalesHaptics()
    val blade = rememberBlade(closed, haptics)
    val frames = rememberSwitchFrames()

    BoxWithConstraints(modifier) {
        val width = with(LocalDensity.current) { maxWidth.toPx() }
        frames.request(width.roundToInt())

        Box(
            Modifier
                .fillMaxSize()
                .switchSemantics(closed)
                .dragToThrow(enabled, blade, closed, haptics, onToggle)
                .holdToThrow(enabled, blade, closed, haptics, onToggle, onHint),
        ) {
            frames.loaded?.let { sequence ->
                Canvas(Modifier.fillMaxSize()) { drawSwitch(sequence, blade.travel, energised) }
            }
        }
    }
}

private fun Modifier.switchSemantics(closed: Boolean): Modifier = semantics {
    role = Role.Switch
    contentDescription = ACTION_DESCRIPTION
    stateDescription = if (closed) CLOSED_DESCRIPTION else OPEN_DESCRIPTION
}

private fun Modifier.dragToThrow(
    enabled: Boolean,
    blade: Blade,
    closed: Boolean,
    haptics: ZalesHaptics,
    onToggle: (Boolean) -> Unit,
): Modifier = pointerInput(enabled, closed) {
    if (!enabled) return@pointerInput
    detectVerticalDragGestures(
        onDragStart = { blade.grab() },
        onDragEnd = {
            // Past the latch it snaps shut; short of it, gravity wins.
            val thrown = blade.release()
            if (thrown != closed) {
                if (thrown) haptics.latch() else haptics.opened()
                onToggle(thrown)
            }
        },
        onDragCancel = { blade.letGo() },
        onVerticalDrag = { change, dragAmount ->
            change.consume()
            // Dragging up closes it: lifting the handle against gravity.
            blade.moveTo(blade.travel - dragAmount / (size.height * Blade.THROW_TRAVEL_FRACTION))
        },
    )
}

private fun Modifier.holdToThrow(
    enabled: Boolean,
    blade: Blade,
    closed: Boolean,
    haptics: ZalesHaptics,
    onToggle: (Boolean) -> Unit,
    onHint: () -> Unit,
): Modifier = pointerInput(enabled, closed) {
    if (!enabled) return@pointerInput
    detectTapGestures(
        onPress = {
            haptics.grasp()
            when (withTimeoutOrNull(Blade.HOLD_TO_THROW_MS) { tryAwaitRelease() }) {
                // Let go at once: that was a touch, not a throw. Say so, once.
                true -> onHint()
                // Still down after the dwell: carry it over for them.
                null -> {
                    blade.driveOver(!closed)
                    if (closed) haptics.opened() else haptics.latch()
                    onToggle(!closed)
                    tryAwaitRelease()
                }
                // Cancelled: the drag detector took the gesture.
                else -> Unit
            }
        },
    )
}

private fun DrawScope.drawSwitch(frames: SwitchFrames, travel: Float, energised: Float) {
    val frame = frames.at(travel)
    val scale = minOf(size.width / frame.width, size.height / frame.height)
    val width = (frame.width * scale).roundToInt()
    val height = (frame.height * scale).roundToInt()
    val offset = IntOffset(
        ((size.width - width) / 2f).roundToInt(),
        ((size.height - height) / 2f).roundToInt(),
    )
    drawImage(frame, dstOffset = offset, dstSize = IntSize(width, height))

    // Current flowing: the same frame with the contacts glowing, cross-faded so
    // that nothing can ghost.
    val glow = frames.energised
    if (glow != null && energised > 0f && travel <= Blade.CLOSED_ENOUGH) {
        drawImage(glow, dstOffset = offset, dstSize = IntSize(width, height), alpha = energised.coerceIn(0f, 1f))
    }
}

/** Loads the sequence once the on-screen size is known, and frees it on the way out. */
@Composable
private fun rememberSwitchFrames(): SwitchFrameLoader {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = remember { SwitchFrameLoader() }
    DisposableEffect(loader) {
        onDispose { loader.release() }
    }
    loader.bind(context, scope)
    return loader
}

private class SwitchFrameLoader {
    var loaded by mutableStateOf<SwitchFrames?>(null)
        private set

    private var requestedWidth = 0
    private var job: Job? = null
    private var context: android.content.Context? = null
    private var scope: kotlinx.coroutines.CoroutineScope? = null

    fun bind(context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope) {
        this.context = context
        this.scope = scope
    }

    fun request(width: Int) {
        val clamped = width.coerceIn(MIN_FRAME_PX, MAX_FRAME_PX)
        if (clamped == requestedWidth) return
        requestedWidth = clamped
        val host = context ?: return
        job?.cancel()
        job = scope?.launch {
            val frames = SwitchFrames.load(host, clamped)
            loaded?.recycle()
            loaded = frames
        }
    }

    fun release() {
        job?.cancel()
        loaded?.recycle()
        loaded = null
    }
}

private const val MIN_FRAME_PX = 240
private const val MAX_FRAME_PX = 520

private const val ACTION_DESCRIPTION = "Рубильник"
private const val CLOSED_DESCRIPTION = "Опущен, тропа открыта"
private const val OPEN_DESCRIPTION = "Поднят, тропа закрыта"
