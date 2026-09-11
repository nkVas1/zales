// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.diagnostics

import io.github.nkvas1.zales.tunnel.api.FailureCode

/**
 * The ladder, climbed from the bottom (docs/DIAGNOSTICS.md §1).
 *
 * The order is the whole point: the check stops at the first rung that fails,
 * which turns a list of hypotheses into a single answer. "The phone is not on
 * a network" and "the far end refuses us" are different sentences, and a person
 * should never have to choose between them.
 */
public enum class ProbeStep {
    /** Is the phone on a network at all? */
    INTERFACE,

    /** Does the server's name resolve, and to something believable? */
    DNS,

    /** Can a socket reach the port? */
    TCP,

    /** Does the transport come up — TLS, Reality, XHTTP? */
    TRANSPORT,

    /** Does the far end accept who we say we are? */
    AUTH,

    /** Do bytes actually cross, end to end? */
    TUNNEL_HTTP,

    /** Will this phone let the tunnel keep working in the background? */
    BACKGROUND,
}

/** How one rung went. */
public sealed interface StepOutcome {
    /** Not started. */
    public data object Pending : StepOutcome

    /** Being checked right now — this is the one with the moving mark. */
    public data object Running : StepOutcome

    public data class Ok(val millis: Long, val note: String? = null) : StepOutcome

    public data class Failed(val code: FailureCode, val detail: String) : StepOutcome

    /** Never reached, because something below it failed first. */
    public data object Skipped : StepOutcome
}

public data class StepResult(val step: ProbeStep, val outcome: StepOutcome) {
    public val isFailure: Boolean get() = outcome is StepOutcome.Failed
}

/**
 * What the whole check found.
 *
 * [verdict] is null only when every rung passed, which is itself a diagnosis:
 * whatever went wrong is no longer going wrong, and saying so plainly is more
 * honest than inventing a cause.
 */
public data class Diagnosis(
    val steps: List<StepResult>,
    val verdict: FailureCode?,
    val detail: String,
    val finished: Boolean,
    /** The English block for whoever gave the key. Filled in only at the end. */
    val report: String = "",
) {
    public val running: ProbeStep? get() = steps.firstOrNull { it.outcome is StepOutcome.Running }?.step

    public companion object {
        /** There is no key yet, so there is nothing to walk the ladder with. */
        public fun nothingToCheck(): Diagnosis = Diagnosis(
            steps = ProbeStep.entries.map { StepResult(it, StepOutcome.Skipped) },
            verdict = FailureCode.KEY_05,
            detail = "no key stored",
            finished = true,
        )

        public fun starting(): Diagnosis = Diagnosis(
            steps = ProbeStep.entries.map { StepResult(it, StepOutcome.Pending) },
            verdict = null,
            detail = "",
            finished = false,
        )
    }
}

/** One strategy tried, and what it cost. Only ever shown in the report. */
public data class StrategyAttempt(val id: String, val ok: Boolean, val millis: Long, val error: String?)
