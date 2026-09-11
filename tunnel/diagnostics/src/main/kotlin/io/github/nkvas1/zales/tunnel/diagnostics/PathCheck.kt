// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.diagnostics

import io.github.nkvas1.zales.model.AccessKey
import io.github.nkvas1.zales.tunnel.api.FailureCode
import io.github.nkvas1.zales.tunnel.api.Tactics
import io.github.nkvas1.zales.tunnel.api.TunnelEngine
import io.github.nkvas1.zales.tunnel.autopilot.StrategyLadder
import io.github.nkvas1.zales.tunnel.autopilot.Uplink
import io.github.nkvas1.zales.tunnel.xray.XrayConfigBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Reads the phone's own connection. Separated so the ladder can be tested. */
public fun interface UplinkReader {
    public fun current(): Uplink
}

/** Answers whether this phone will let the tunnel keep running unattended. */
public fun interface BackgroundReader {
    public fun current(): BackgroundVerdict
}

public data class BackgroundVerdict(val exempt: Boolean, val unexpectedStops: Int, val detail: String)

/** Opens a raw socket to the far end. Real on a phone, scripted in tests. */
public fun interface PortReader {
    /** Milliseconds to connect, or an error string if it never did. */
    public fun connect(address: String, port: Int, timeoutMs: Int): Result<Long>
}

/** Resolves a host name outside the tunnel. */
public fun interface NameReader {
    public fun resolve(host: String): Result<List<InetAddress>>
}

/**
 * Walks the ladder and stops at the first rung that fails.
 *
 * Every rung is a question a person could have asked out loud, in the order
 * they would have asked it, which is why the screen showing this is calming
 * rather than alarming: it is visibly working through the possibilities rather
 * than spinning.
 */
public class PathCheck(
    private val engine: TunnelEngine,
    private val uplink: UplinkReader,
    private val background: BackgroundReader,
    private val ports: PortReader = systemPorts(),
    private val names: NameReader = systemNames(),
    private val environment: () -> Environment,
    private val ladder: StrategyLadder = StrategyLadder(),
    private val probeUrl: String = PROBE_URL,
) {

    /** Emits after each rung, so the screen fills in as the work happens. */
    public fun run(key: AccessKey): Flow<Diagnosis> = flow {
        val sheet = Sheet()

        suspend fun publish(finished: Boolean = false) {
            emit(
                sheet.snapshot(finished) { steps ->
                    Report.render(
                        key = key,
                        environment = environment(),
                        steps = steps,
                        attempts = sheet.attempts,
                        verdict = sheet.verdict,
                        detail = sheet.detail,
                        resolvedAddress = sheet.address,
                    )
                },
            )
        }

        /** Runs one rung and returns false when the ladder should stop here. */
        suspend fun rung(step: ProbeStep, body: () -> StepOutcome): Boolean {
            sheet.mark(step, StepOutcome.Running)
            publish()
            sheet.mark(step, body())
            publish()
            if (sheet.outcomeOf(step) !is StepOutcome.Failed) return true
            sheet.skipTheRest()
            publish(finished = true)
            return false
        }

        publish()
        if (!rung(ProbeStep.INTERFACE) { checkInterface() }) return@flow
        if (!rung(ProbeStep.DNS) { checkName(key).also { sheet.address = it.second }.first }) return@flow
        if (!rung(ProbeStep.TCP) { checkPort(key, sheet.address) }) return@flow

        // The three upper rungs are one measurement seen from three sides: a
        // real request through the proxy either arrives or it does not, and
        // what it says on the way out tells us which rung it fell off.
        val reached = checkPath(key, sheet.address, sheet.attempts)
        sheet.mark(ProbeStep.TRANSPORT, reached.transport)
        sheet.mark(ProbeStep.AUTH, reached.auth)
        sheet.mark(ProbeStep.TUNNEL_HTTP, reached.http)
        publish()

        // Asked last on purpose: it is the only rung that says nothing about
        // today's failure and everything about tomorrow's.
        if (rung(ProbeStep.BACKGROUND) { checkBackground() }) publish(finished = true)
    }.flowOn(Dispatchers.IO)

    /**
     * The running sheet of the check.
     *
     * The first failure is the diagnosis and later ones cannot overwrite it:
     * once the transport is blocked, whatever the battery settings say is a
     * separate conversation, not the answer to today's question.
     */
    private class Sheet {
        private val outcomes = ProbeStep.entries.associateWithTo(mutableMapOf<ProbeStep, StepOutcome>()) {
            StepOutcome.Pending
        }

        val attempts: MutableList<StrategyAttempt> = mutableListOf()
        var verdict: FailureCode? = null
            private set
        var detail: String = ""
            private set
        var address: String? = null

        fun outcomeOf(step: ProbeStep): StepOutcome = outcomes.getValue(step)

        fun mark(step: ProbeStep, outcome: StepOutcome) {
            outcomes[step] = outcome
            if (outcome is StepOutcome.Failed && verdict == null) {
                verdict = outcome.code
                detail = outcome.detail
            }
        }

        fun skipTheRest() {
            ProbeStep.entries
                .filter { outcomes.getValue(it) is StepOutcome.Pending }
                .forEach { outcomes[it] = StepOutcome.Skipped }
        }

        fun snapshot(finished: Boolean, report: (List<StepResult>) -> String): Diagnosis {
            val steps = ProbeStep.entries.map { StepResult(it, outcomes.getValue(it)) }
            return Diagnosis(
                steps = steps,
                verdict = verdict,
                detail = detail,
                finished = finished,
                report = if (finished) report(steps) else "",
            )
        }
    }

    private fun checkInterface(): StepOutcome = when (uplink.current()) {
        Uplink.AIRPLANE -> StepOutcome.Failed(FailureCode.NET_03, "airplane mode is on")
        Uplink.OFFLINE -> StepOutcome.Failed(FailureCode.NET_01, "no active network")
        Uplink.CAPTIVE -> StepOutcome.Failed(FailureCode.NET_02, "network is behind a captive portal")
        Uplink.READY -> StepOutcome.Ok(0)
    }

    private fun checkName(key: AccessKey): Pair<StepOutcome, String?> {
        val host = key.endpoint.host
        // An address in the key needs no resolver, and saying so is better than
        // inventing a step that did not happen. Checked by shape rather than by
        // calling the resolver, which for a name would block on the very lookup
        // this branch exists to avoid.
        if (host.none { it.isLetter() } || ':' in host) {
            return StepOutcome.Ok(0, "address given in the key") to host
        }

        val started = System.currentTimeMillis()
        val answers = names.resolve(host).getOrElse { error ->
            return StepOutcome.Failed(FailureCode.SRV_01, error.message ?: "resolver returned nothing") to null
        }
        val took = System.currentTimeMillis() - started
        val usable = answers.firstOrNull { !Verdicts.isStubAnswer(it) }
        if (usable == null) {
            val stub = answers.firstOrNull()?.hostAddress ?: "nothing"
            return StepOutcome.Failed(FailureCode.SRV_01, "resolver answered with a stub address ($stub)") to null
        }
        val note = if (answers.size > 1) "${answers.size} addresses" else null
        return StepOutcome.Ok(took, note) to usable.hostAddress
    }

    private fun checkPort(key: AccessKey, address: String?): StepOutcome {
        val target = address ?: key.endpoint.host
        return ports.connect(target, key.endpoint.port, CONNECT_TIMEOUT_MS).fold(
            onSuccess = { StepOutcome.Ok(it) },
            onFailure = { error ->
                StepOutcome.Failed(Verdicts.forConnect(error.message), error.message ?: "could not connect")
            },
        )
    }

    private data class Upper(val transport: StepOutcome, val auth: StepOutcome, val http: StepOutcome) {
        val failure: StepOutcome.Failed?
            get() = listOf(transport, auth, http).filterIsInstance<StepOutcome.Failed>().firstOrNull()
    }

    /**
     * Tries the whole ladder, not just the baseline.
     *
     * A check that only tried the plain way would report "blocked" on a network
     * where the tunnel would in fact come up with fragmentation — and the whole
     * point of the report is that the person holding it can trust it.
     */
    private fun checkPath(key: AccessKey, address: String?, attempts: MutableList<StrategyAttempt>): Upper {
        val rungs = ladder.rungsFor(key, address)
        val results = engine.probe(rungs.map { XrayConfigBuilder.forProbe(key, it) }, probeUrl, PROBE_TIMEOUT_MS)
        rungs.forEachIndexed { index, tactics ->
            val result = results.getOrNull(index)
            attempts += StrategyAttempt(
                id = tactics.id.value,
                ok = result?.success == true,
                millis = result?.delayMs ?: 0,
                error = result?.error,
            )
        }

        val winner = attempts.filter { it.ok }.minByOrNull { it.millis }
        if (winner != null) {
            val note = if (winner.id == Tactics.Baseline.id.value) null else "needed ${winner.id}"
            return Upper(
                transport = StepOutcome.Ok(winner.millis, note),
                auth = StepOutcome.Ok(0),
                http = StepOutcome.Ok(winner.millis),
            )
        }

        val error = attempts.firstNotNullOfOrNull { it.error } ?: "no strategy answered"
        val code = Verdicts.forHandshake(error)
        // Every rung failed the same way and the socket itself was fine: that is
        // the network refusing this kind of conversation, not a broken server.
        val everySilent = attempts.size > 1 && attempts.all { Verdicts.forHandshake(it.error) == FailureCode.DPI_01 }
        val failure = StepOutcome.Failed(if (everySilent) FailureCode.DPI_01 else code, error)
        return when (failure.code) {
            FailureCode.SRV_05 -> Upper(StepOutcome.Ok(0), failure, StepOutcome.Skipped)
            else -> Upper(failure, StepOutcome.Skipped, StepOutcome.Skipped)
        }
    }

    private fun checkBackground(): StepOutcome {
        val verdict = background.current()
        return when {
            verdict.exempt -> StepOutcome.Ok(0, verdict.detail)
            verdict.unexpectedStops > 0 ->
                StepOutcome.Failed(FailureCode.SYS_04, "killed ${verdict.unexpectedStops}× while connected")
            // Not exempt but never actually killed: worth saying, not worth failing.
            else -> StepOutcome.Ok(0, "battery optimisation is on, but nothing has been cut off yet")
        }
    }

    public companion object {
        /** Answers 204 with an empty body, so a probe measures the path and nothing else. */
        public const val PROBE_URL: String = "https://cp.cloudflare.com/generate_204"

        private const val PROBE_TIMEOUT_MS = 6_000
        private const val CONNECT_TIMEOUT_MS = 4_000

        /** A plain socket to the port, closed the moment it opens. */
        public fun systemPorts(): PortReader = PortReader { address, port, timeoutMs ->
            runCatching {
                val started = System.currentTimeMillis()
                Socket().use { it.connect(InetSocketAddress(address, port), timeoutMs) }
                System.currentTimeMillis() - started
            }
        }

        public fun systemNames(): NameReader = NameReader { host ->
            runCatching { InetAddress.getAllByName(host).toList() }
        }
    }
}
