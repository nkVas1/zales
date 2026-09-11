// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nkvas1.zales.design.Zales
import io.github.nkvas1.zales.design.component.PlateButton
import io.github.nkvas1.zales.design.component.ZalesText
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
                gaze = state.gaze,
            ),
            modifier = Modifier.fillMaxSize(),
        )

        Panel(state, current, onToggle, onAction, onHint)
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
) {
    val tunnel = state.tunnel
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZalesText(
            text = state.plate,
            style = Zales.type.nameplate,
            color = Zales.colors.rime,
            modifier = Modifier.fillMaxWidth(),
            align = TextAlign.End,
        )

        Spacer(Modifier.weight(TOP_WEIGHT))

        ZalesText(
            text = stringResource(Words.stateWord(tunnel)),
            style = Zales.type.state,
            color = if (tunnel is TunnelState.Connected) Zales.colors.lamp else Zales.colors.bone,
            align = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))

        KnifeSwitch(
            closed = tunnel.isEngaged,
            enabled = state.canSwitch,
            onToggle = onToggle,
            onHint = onHint,
            energised = current,
            modifier = Modifier
                .fillMaxWidth()
                .weight(SWITCH_WEIGHT)
                .sizeIn(minHeight = 220.dp),
        )

        Guidance(state, onAction)

        Spacer(Modifier.weight(BOTTOM_WEIGHT))

        ZalesText(
            text = state.saying.orEmpty(),
            style = Zales.type.caption,
            color = Zales.colors.rime,
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The strip under the switch, which is empty almost all of the time.
 *
 * Two things can appear here and they can never appear together: what went
 * wrong, or — the first few times only — how the handle is meant to be moved.
 */
@Composable
private fun Guidance(state: HomeUiState, onAction: (FailureAction) -> Unit) {
    val failure = (state.tunnel as? TunnelState.Failed)?.failure
    when {
        failure != null -> Explanation(failure.code, onAction)
        state.hint -> Hint()
    }
}

/** Said once and then let go of, the way you would tell someone in person. */
@Composable
private fun Hint() {
    ZalesText(
        text = stringResource(Words.throwHint),
        style = Zales.type.body,
        color = Zales.colors.rime,
        align = TextAlign.Center,
        modifier = Modifier.padding(top = 16.dp, start = 24.dp, end = 24.dp),
    )
}

/** One sentence and at most one thing to do. Never a code, never a list. */
@Composable
private fun Explanation(code: FailureCode, onAction: (FailureAction) -> Unit) {
    val action = Words.action(code)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 16.dp),
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

private const val TOP_WEIGHT = 0.8f
private const val SWITCH_WEIGHT = 3.2f
private const val BOTTOM_WEIGHT = 0.6f
private const val CLEARING_MS = 900
private const val CURRENT_MS = 600
