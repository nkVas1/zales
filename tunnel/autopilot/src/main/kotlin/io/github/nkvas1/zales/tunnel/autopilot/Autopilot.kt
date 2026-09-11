// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.autopilot

import io.github.nkvas1.zales.common.ZalesLog
import io.github.nkvas1.zales.model.AccessKey
import io.github.nkvas1.zales.tunnel.api.Tactics
import io.github.nkvas1.zales.tunnel.api.TunnelEngine
import io.github.nkvas1.zales.tunnel.xray.XrayConfigBuilder

/** How the server was reached, and how quickly. */
public sealed interface Choice {
    public data class Found(val tactics: Tactics, val latencyMs: Int) : Choice

    /** Nothing worked. [detail] is English technical text for the report. */
    public data class None(val detail: String) : Choice
}

/** Resolves a host without going through the tunnel. */
public fun interface AddressResolver {
    public suspend fun resolve(host: String): String?
}

/**
 * Decides how to reach the server today, and remembers what worked here.
 *
 * Strategies are not tried one after another: every rung of the ladder is
 * probed at once and the first real answer wins, so the whole search costs one
 * round trip rather than one per rung (docs/CONNECTIVITY.md §3).
 *
 * Success is an actual HTTP response through the proxy. A TCP connection proves
 * nothing — Xray's stack accepts connections locally, and a frozen path looks
 * identical to a live one until bytes are asked of it.
 */
public class Autopilot(
    private val engine: TunnelEngine,
    private val profiles: NetworkProfiles,
    private val resolver: AddressResolver,
    private val ladder: StrategyLadder = StrategyLadder(),
    private val probeUrl: String = PROBE_URL,
    private val probeTimeoutMs: Int = PROBE_TIMEOUT_MS,
) {

    public suspend fun choose(key: AccessKey, network: NetworkFingerprint): Choice {
        val resolved = resolver.resolve(key.endpoint.host)
        val rungs = ladder.rungsFor(key, resolved)

        // What worked here last time goes first, alone: one probe instead of six.
        profiles.remembered(network)?.let { remembered ->
            rungs.firstOrNull { it.id == remembered }?.let { tactics ->
                probeOne(key, tactics)?.let { latency ->
                    ZalesLog.info(ZalesLog.TAG_TUNNEL, "autopilot reused ${tactics.id} on a known network")
                    return Choice.Found(tactics, latency)
                }
                ZalesLog.info(ZalesLog.TAG_TUNNEL, "autopilot: remembered strategy no longer works, racing")
            }
        }

        return race(key, rungs, network)
    }

    /**
     * Measures the ladder in waves, stopping at the first wave that answers.
     *
     * The core will not measure more than a handful of ways in at once, and a
     * batch over that limit is rejected whole rather than trimmed — so a ladder
     * longer than one wave is raced in two, best candidates first. In practice
     * the first wave wins and this costs exactly what it always did.
     */
    private fun race(key: AccessKey, rungs: List<Tactics>, network: NetworkFingerprint): Choice {
        var firstError: String? = null
        rungs.chunked(engine.probeBatchLimit).forEach { wave ->
            val results = engine.probe(wave.map { XrayConfigBuilder.forProbe(key, it) }, probeUrl, probeTimeoutMs)
            firstError = firstError ?: results.firstNotNullOfOrNull { it.error }
            val winner = wave.indices
                .mapNotNull { index -> results.getOrNull(index)?.takeIf { it.success }?.let { index to it } }
                .minByOrNull { (_, result) -> result.delayMs }
            if (winner != null) {
                val (index, result) = winner
                val tactics = wave[index]
                profiles.remember(network, tactics.id)
                ZalesLog.info(ZalesLog.TAG_TUNNEL, "autopilot chose ${tactics.id} in ${result.delayMs} ms")
                return Choice.Found(tactics, result.delayMs.toInt())
            }
        }
        ZalesLog.warn(ZalesLog.TAG_TUNNEL, "autopilot exhausted ${rungs.size} strategies")
        return Choice.None(firstError ?: "every strategy failed")
    }

    /** Probes a single rung. Returns its latency, or null if it did not answer. */
    private fun probeOne(key: AccessKey, tactics: Tactics): Int? =
        engine.probe(listOf(XrayConfigBuilder.forProbe(key, tactics)), probeUrl, probeTimeoutMs)
            .firstOrNull()
            ?.takeIf { it.success }
            ?.delayMs
            ?.toInt()

    public companion object {
        /** Answers 204 with an empty body, so a probe measures the path and nothing else. */
        public const val PROBE_URL: String = "https://cp.cloudflare.com/generate_204"
        public const val PROBE_TIMEOUT_MS: Int = 8_000
    }
}
