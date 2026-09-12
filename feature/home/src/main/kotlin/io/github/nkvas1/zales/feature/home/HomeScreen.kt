// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nkvas1.zales.design.Motion
import io.github.nkvas1.zales.design.Zales
import io.github.nkvas1.zales.design.component.PlateButton
import io.github.nkvas1.zales.design.component.ZalesText
import io.github.nkvas1.zales.design.component.linesHigh
import io.github.nkvas1.zales.design.switchboard.KnifeSwitch
import io.github.nkvas1.zales.design.thicket.ThicketState
import io.github.nkvas1.zales.design.thicket.ThicketSurface
import io.github.nkvas1.zales.tunnel.api.FailureCode
import io.github.nkvas1.zales.tunnel.api.TunnelState
import io.github.nkvas1.zales.tunnel.api.isEngaged
import io.github.nkvas1.zales.words.FailureAction
import io.github.nkvas1.zales.words.Words

/**
 * The whole app, as far as most people will ever need to go: a forest, a word,
 * and a switch.
 *
 * Nothing else competes for attention. What state the path is in is readable
 * from across a room, and the only thing that can be touched is the handle.
 */
@Composable
public fun HomeScreen(
    state: HomeUiState,
    onToggle: (Boolean) -> Unit,
    onAction: (FailureAction) -> Unit,
    onHint: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tunnel = state.tunnel
    val openness by animateFloatAsState(
        targetValue = tunnel.clearing,
        animationSpec = tween(durationMillis = CLEARING_MS),
        label = "clearing",
    )
    val current by animateFloatAsState(
        targetValue = when (tunnel) {
            is TunnelState.Connected -> 1f
            is TunnelState.Degraded -> CURRENT_DEGRADED
            else -> 0f
        },
        animationSpec = tween(durationMillis = CURRENT_MS),
        label = "current",
    )

    Box(modifier.fillMaxSize()) {
        ThicketSurface(
            state = ThicketState(
                open = openness,
                pulse = state.pulse,
                // A failure stills the forest: no breathing, no watching.
                alive = tunnel !is TunnelState.Failed,
                // Being watched from the trees is the one deliberately unsettling
                // thing here, and it is exactly what somebody asking for less
                // movement is asking to be spared.
                gaze = if (Motion.stilled) 0f else state.gaze,
            ),
            modifier = Modifier.fillMaxSize(),
        )

        Panel(state, current, onToggle, onAction, onHint, onOpenSettings)
    }
}

/** Everything that stands in front of the forest. */
@Composable
private fun Panel(
    state: HomeUiState,
    current: Float,
    onToggle: (Boolean) -> Unit,
    onAction: (FailureAction) -> Unit,
    onHint: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val tunnel = state.tunnel
    // At the largest system font sizes the composed layout no longer fits any
    // screen, and a weight would silently crush the switch instead of the text.
    // Above that point the panel becomes an ordinary scrolling column: the
    // switch keeps a real size and everything else is simply reachable.
    val roomy = LocalDensity.current.fontScale <= LARGE_TEXT

    // Everything that is not the switch has a height that does not depend on
    // what is in it. That leaves exactly one flexible slot in the column — the
    // switch — so the space it gets is the same in every state, and a saying
    // that runs to two lines instead of one cannot move it by a pixel.
    val strip = linesHigh(Zales.type.body, lines = MESSAGE_LINES) + ACTION_ROOM

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .then(if (roomy) Modifier else Modifier.verticalScroll(rememberScrollState()))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The engraved plate in the corner is also the way into the settings.
        // Small, out of the way, and the only door there is — which is exactly
        // right for a screen that must not offer anyone a decision.
        ZalesText(
            text = state.plate,
            style = Zales.type.nameplate,
            color = Zales.colors.rime,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClickLabel = stringResource(R.string.a11y_open_settings),
                    onClick = onOpenSettings,
                ),
            align = TextAlign.End,
        )

        Spacer(Modifier.height(24.dp))

        // One line, always, in a slot of its own height. «ТРОПА СУЗИЛАСЬ» is
        // twice the length of «ОТКРЫТО» and must not push the switch down.
        Box(
            modifier = Modifier.fillMaxWidth().height(linesHigh(Zales.type.state, lines = 1)),
            contentAlignment = Alignment.Center,
        ) {
            ZalesText(
                text = stringResource(Words.stateWord(tunnel)),
                style = Zales.type.state,
                color = if (tunnel is TunnelState.Connected) Zales.colors.lamp else Zales.colors.bone,
                align = TextAlign.Center,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(16.dp))

        KnifeSwitch(
            closed = tunnel.isEngaged,
            enabled = state.canSwitch,
            onToggle = onToggle,
            onHint = onHint,
            energised = current,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (roomy) Modifier.weight(1f) else Modifier.height(SWITCH_HEIGHT))
                .sizeIn(minHeight = SWITCH_HEIGHT),
        )

        Spacer(Modifier.height(12.dp))

        // One strip at the foot of the screen, always the same height, holding
        // whichever of the two things there is to say — never both.
        Box(modifier = Modifier.fillMaxWidth().height(strip), contentAlignment = Alignment.TopCenter) {
            Guidance(state, onAction)
        }
    }
}

/**
 * The strip under the switch, which holds one small sentence at most.
 *
 * Three things can appear here and never two at once: what went wrong, the
 * first-run request for a key, or — in the quiet — a saying. The order is the
 * order of importance, and trouble silences the small talk completely.
 */
@Composable
private fun Guidance(state: HomeUiState, onAction: (FailureAction) -> Unit) {
    val failure = (state.tunnel as? TunnelState.Failed)?.failure
    when {
        failure != null -> Explanation(failure.code, onAction)
        // Said before anything is touched rather than after: with no key the
        // handle cannot move, and a person should never be left pulling at
        // something that was never going to give.
        state.needsKey -> Explanation(FailureCode.KEY_05, onAction)
        state.hint -> Hint()
        else -> Saying(state.saying)
    }
}

/** The quiet line. Two lines of room, whether it uses them or not. */
@Composable
private fun Saying(text: String?) {
    ZalesText(
        text = text.orEmpty(),
        style = Zales.type.caption,
        color = Zales.colors.rime,
        align = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Said once and then let go of, the way you would tell someone in person. */
@Composable
private fun Hint() {
    ZalesText(
        text = stringResource(Words.throwHint),
        style = Zales.type.body,
        color = Zales.colors.rime,
        align = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

/** One sentence and at most one thing to do. Never a code, never a list. */
@Composable
private fun Explanation(code: FailureCode, onAction: (FailureAction) -> Unit) {
    val action = Words.action(code)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZalesText(
            text = stringResource(Words.sentence(code)),
            style = Zales.type.body,
            color = Zales.colors.bone,
            align = TextAlign.Center,
        )
        if (action != null) {
            PlateButton(text = stringResource(action.label), onClick = { onAction(action) })
        }
    }
}

/** How far the forest has parted, per state (docs/DESIGN.md §12). */
private val TunnelState.clearing: Float
    get() = when (this) {
        TunnelState.Idle, TunnelState.Stopping, is TunnelState.Failed -> CLEARING_SHUT
        TunnelState.Preparing -> CLEARING_STIRRING
        is TunnelState.Probing -> CLEARING_SEARCHING
        is TunnelState.Reconnecting -> CLEARING_DETOUR
        is TunnelState.Degraded -> CLEARING_NARROWED
        is TunnelState.Connected -> CLEARING_OPEN
    }

private const val CLEARING_SHUT = 0f
private const val CLEARING_STIRRING = 0.18f
private const val CLEARING_SEARCHING = 0.45f
private const val CLEARING_DETOUR = 0.5f
private const val CLEARING_NARROWED = 0.55f
private const val CLEARING_OPEN = 1f
private const val CURRENT_DEGRADED = 0.45f

private val SWITCH_HEIGHT = 220.dp

/** Room for the longest sentence in the taxonomy without the strip growing. */
private const val MESSAGE_LINES = 3

/** Room under it for one plate button, plus the gap above the button. */
private val ACTION_ROOM = 68.dp

/** Past this the system font is large enough that a fixed layout stops fitting. */
private const val LARGE_TEXT = 1.5f
private const val CLEARING_MS = 900
private const val CURRENT_MS = 600
