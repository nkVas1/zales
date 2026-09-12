// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.feature.diagnostics

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nkvas1.zales.design.Motion
import io.github.nkvas1.zales.design.Zales
import io.github.nkvas1.zales.design.component.PlateButton
import io.github.nkvas1.zales.design.component.ZalesText
import io.github.nkvas1.zales.tunnel.diagnostics.ProbeStep
import io.github.nkvas1.zales.tunnel.diagnostics.StepOutcome
import io.github.nkvas1.zales.tunnel.diagnostics.StepResult
import io.github.nkvas1.zales.words.FailureAction
import io.github.nkvas1.zales.words.Words

/**
 * «Проверка тропы»: the screen that answers "so what is actually wrong?".
 *
 * The rungs are checked one at a time and each one keeps its own line, so the
 * person can see the work happening and see exactly where it stopped. That is
 * the whole reason this screen exists rather than a spinner: a list filling in
 * is reassuring, and a spinner is not.
 */
@Composable
public fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onAction: (FailureAction) -> Unit,
    onCopyReport: () -> Unit,
    onToggleReport: () -> Unit,
    onRepeat: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Zales.colors.void)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ZalesText(text = stringResource(R.string.check_title), style = Zales.type.title, color = Zales.colors.bone)
        ZalesText(
            text = stringResource(R.string.check_intro),
            style = Zales.type.body,
            color = Zales.colors.rime,
        )

        Spacer(Modifier.height(4.dp))
        state.steps.forEach { StepLine(it) }

        Verdict(state, onAction)
        TechnicalBlock(state, onToggleReport, onCopyReport)

        Spacer(Modifier.height(4.dp))
        if (state.finished) {
            PlateButton(
                text = stringResource(R.string.check_again),
                onClick = onRepeat,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PlateButton(
            text = stringResource(R.string.check_back),
            onClick = onLeave,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One rung: a mark, and the question it answers. */
@Composable
private fun StepLine(result: StepResult) {
    val outcome = result.outcome
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Mark(outcome)
        Spacer(Modifier.width(14.dp))
        ZalesText(
            text = stringResource(result.step.label) + if (outcome is StepOutcome.Running) "…" else "",
            style = Zales.type.body,
            color = outcome.textColour,
        )
    }
}

/**
 * The mark in the left column.
 *
 * The one being worked on breathes, slowly. Nothing else on this screen moves,
 * so a single pulsing square carries the whole idea of "this is where I am now"
 * without a spinner anywhere.
 */
@Composable
private fun Mark(outcome: StepOutcome) {
    val glyph = when (outcome) {
        StepOutcome.Pending, StepOutcome.Skipped -> "·"
        StepOutcome.Running -> "◐"
        is StepOutcome.Ok -> "▣"
        is StepOutcome.Failed -> "▨"
    }
    val breathing by rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = BREATH_LOW,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS), RepeatMode.Reverse),
        label = "breath",
    )
    val moving = outcome is StepOutcome.Running && !Motion.stilled
    ZalesText(
        text = glyph,
        style = Zales.type.body,
        color = outcome.markColour,
        modifier = Modifier
            .width(MARK_WIDTH)
            .alpha(if (moving) breathing else 1f)
            // The mark repeats what the line already says; a screen reader
            // should not have to read a square out loud.
            .clearAndSetSemantics { },
    )
}

/** One sentence about what happened, and at most one thing to do about it. */
@Composable
private fun Verdict(state: DiagnosticsUiState, onAction: (FailureAction) -> Unit) {
    if (!state.finished) return
    val code = state.verdict
    Spacer(Modifier.height(6.dp))
    if (code == null) {
        ZalesText(
            text = stringResource(R.string.check_all_well),
            style = Zales.type.body,
            color = Zales.colors.lamp,
        )
        return
    }
    ZalesText(text = stringResource(Words.sentence(code)), style = Zales.type.body, color = Zales.colors.bone)
    Words.action(code)?.let { action ->
        PlateButton(
            text = stringResource(action.label),
            onClick = { onAction(action) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The block for whoever gave the key. Folded away by default, because it is
 * written in another language and for another person.
 */
@Composable
private fun TechnicalBlock(state: DiagnosticsUiState, onToggle: () -> Unit, onCopy: () -> Unit) {
    if (!state.finished || state.report.isBlank()) return

    PlateButton(
        text = stringResource(if (state.reportOpen) R.string.check_report_hide else R.string.check_report_show),
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
    )
    if (!state.reportOpen) return

    ZalesText(
        text = stringResource(R.string.check_report_warning),
        style = Zales.type.caption,
        color = Zales.colors.rime,
    )
    ZalesText(
        text = state.report,
        style = Zales.type.nameplate,
        color = Zales.colors.rime,
        modifier = Modifier
            .fillMaxWidth()
            .background(Zales.colors.deep, REPORT_SHAPE)
            .border(1.dp, Zales.colors.cold, REPORT_SHAPE)
            .padding(14.dp)
            // Fixed columns only line up if they are allowed not to wrap.
            .horizontalScroll(rememberScrollState()),
        align = TextAlign.Start,
    )
    PlateButton(
        text = stringResource(R.string.check_report_copy),
        onClick = onCopy,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.reportCopied) {
        ZalesText(
            text = stringResource(R.string.check_report_copied),
            style = Zales.type.caption,
            color = Zales.colors.lamp,
        )
    }
}

private val StepOutcome.markColour: Color
    @Composable get() = when (this) {
        StepOutcome.Running -> Zales.colors.lamp
        is StepOutcome.Failed -> Zales.colors.rust
        else -> Zales.colors.rime
    }

private val StepOutcome.textColour: Color
    @Composable get() = when (this) {
        StepOutcome.Running, is StepOutcome.Ok -> Zales.colors.bone
        is StepOutcome.Failed -> Zales.colors.bone
        else -> Zales.colors.rime
    }

private val ProbeStep.label: Int
    get() = when (this) {
        ProbeStep.INTERFACE -> R.string.check_step_interface
        ProbeStep.DNS -> R.string.check_step_dns
        ProbeStep.TCP -> R.string.check_step_tcp
        ProbeStep.TRANSPORT -> R.string.check_step_transport
        ProbeStep.AUTH -> R.string.check_step_auth
        ProbeStep.TUNNEL_HTTP -> R.string.check_step_http
        ProbeStep.BACKGROUND -> R.string.check_step_background
    }

private val MARK_WIDTH = 26.dp
private val REPORT_SHAPE = RoundedCornerShape(2.dp)
private const val BREATH_MS = 900
private const val BREATH_LOW = 0.35f
