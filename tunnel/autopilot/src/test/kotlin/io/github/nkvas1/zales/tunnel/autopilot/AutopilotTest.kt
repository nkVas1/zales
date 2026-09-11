// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.autopilot

import io.github.nkvas1.zales.model.AccessKey
import io.github.nkvas1.zales.model.Endpoint
import io.github.nkvas1.zales.model.Security
import io.github.nkvas1.zales.model.Transport
import io.github.nkvas1.zales.tunnel.api.EngineConfig
import io.github.nkvas1.zales.tunnel.api.EngineResult
import io.github.nkvas1.zales.tunnel.api.ProbeResult
import io.github.nkvas1.zales.tunnel.api.SocketProtector
import io.github.nkvas1.zales.tunnel.api.StrategyId
import io.github.nkvas1.zales.tunnel.api.TunnelEngine
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutopilotTest {

    private val realityKey = AccessKey.Vless(
        label = "home",
        endpoint = Endpoint("vpn.example.com", 443),
        uuid = "id",
        flow = "xtls-rprx-vision",
        encryption = "none",
        transport = Transport.Tcp(),
        security = Security.Reality("vkvideo.ru", "PBK", "ab", "chrome", null, null),
    )

    private val outlineKey = AccessKey.Shadowsocks(
        label = "o",
        endpoint = Endpoint("198.51.100.4", 8388),
        method = "chacha20-ietf-poly1305",
        password = "pw",
        prefix = null,
    )

    /** Answers probes from a script, and records how many times it was asked. */
    private class ScriptedEngine(private val script: (Int) -> ProbeResult) : TunnelEngine {
        var batches = 0
            private set
        var probed = 0
            private set

        override val version: String = "test"
        override fun setProtector(protector: SocketProtector?) = EngineResult.Ok(Unit)
        override fun validate(config: EngineConfig) = EngineResult.Ok(Unit)
        override fun start(config: EngineConfig, tunFd: Int) = EngineResult.Ok(Unit)
        override fun stop() = EngineResult.Ok(Unit)
        override fun isRunning() = false

        override fun probe(configs: List<EngineConfig>, url: String, timeoutMs: Int): List<ProbeResult> {
            batches++
            probed += configs.size
            return configs.indices.map(script)
        }
    }

    private class Profiles : NetworkProfiles {
        private val map = mutableMapOf<String, StrategyId>()
        override fun remembered(network: NetworkFingerprint) = map[network.id]
        override fun remember(network: NetworkFingerprint, strategy: StrategyId) { map[network.id] = strategy }
        override fun forget(network: NetworkFingerprint) { map.remove(network.id) }
    }

    private val here = NetworkFingerprint("wifi:test")
    private fun resolver(address: String? = null) = AddressResolver { address }

    private fun failure(error: String = "timeout") = ProbeResult(success = false, delayMs = 0, error = error)
    private fun success(delay: Long) = ProbeResult(success = true, delayMs = delay, error = null)

    @Test
    fun `a plain key with no TLS has nothing to fragment`() {
        val rungs = StrategyLadder().rungsFor(outlineKey)
        assertEquals(listOf("s0"), rungs.map { it.id.value })
    }

    @Test
    fun `a TLS key on 443 gets fragments, fingerprints and CDN ports`() {
        val rungs = StrategyLadder().rungsFor(realityKey, resolvedAddress = "203.0.113.7").map { it.id.value }
        assertEquals("s0", rungs.first())
        assertTrue("s1" in rungs && "s1+s2" in rungs, "fragmentation must be on the ladder: $rungs")
        assertTrue("s5" in rungs, "dialling the resolved address must be on the ladder: $rungs")
        assertTrue(rungs.any { it.startsWith("s4:") }, "alternate ports belong at the end: $rungs")
    }

    @Test
    fun `XHTTP keys are offered packet-up, which the freeze cannot touch`() {
        val key = realityKey.copy(flow = null, transport = Transport.Xhttp("/x", null, "auto", null))
        val rungs = StrategyLadder().rungsFor(key)
        val packetUp = rungs.filter { it.xhttpMode == StrategyLadder.PACKET_UP }
        assertTrue(packetUp.isNotEmpty(), "an XHTTP key must be offered packet-up")
    }

    @Test
    fun `the whole ladder is raced at once and the quickest answer wins`() = runTest {
        val engine = ScriptedEngine { index ->
            when (index) {
                0 -> failure()
                1 -> success(delay = 420)
                2 -> success(delay = 180)
                else -> failure()
            }
        }
        val profiles = Profiles()
        val choice = Autopilot(engine, profiles, resolver()).choose(realityKey, here)

        assertTrue(choice is Choice.Found, "expected a winner, got $choice")
        choice as Choice.Found
        assertEquals(180, choice.latencyMs)
        assertEquals(1, engine.batches, "the race must cost one round trip, not one per rung")
        assertEquals(choice.tactics.id, profiles.remembered(here))
    }

    @Test
    fun `a known network is tried with one probe, not a whole race`() = runTest {
        val profiles = Profiles().apply { remember(here, StrategyId("s1")) }
        val engine = ScriptedEngine { success(delay = 90) }

        val choice = Autopilot(engine, profiles, resolver()).choose(realityKey, here) as Choice.Found

        assertEquals("s1", choice.tactics.id.value)
        assertEquals(1, engine.probed, "a remembered strategy should be probed alone")
    }

    @Test
    fun `when the remembered strategy stops working the race starts again`() = runTest {
        val profiles = Profiles().apply { remember(here, StrategyId("s1")) }
        var call = 0
        val engine = ScriptedEngine { index ->
            // The first call is the remembered strategy on its own, and it fails.
            if (call++ == 0) failure() else if (index == 1) success(delay = 250) else failure()
        }

        val choice = Autopilot(engine, profiles, resolver()).choose(realityKey, here)

        assertTrue(choice is Choice.Found, "the ladder should have rescued it, got $choice")
        assertEquals(2, engine.batches)
    }

    @Test
    fun `when nothing answers the reason is carried out for the report`() = runTest {
        val engine = ScriptedEngine { failure("handshake timeout") }
        val choice = Autopilot(engine, Profiles(), resolver()).choose(realityKey, here)

        assertTrue(choice is Choice.None)
        assertEquals("handshake timeout", (choice as Choice.None).detail)
    }
}
