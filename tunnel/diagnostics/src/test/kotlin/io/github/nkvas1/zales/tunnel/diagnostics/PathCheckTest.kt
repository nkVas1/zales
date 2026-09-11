// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.diagnostics

import io.github.nkvas1.zales.model.AccessKey
import io.github.nkvas1.zales.model.Endpoint
import io.github.nkvas1.zales.model.Security
import io.github.nkvas1.zales.model.Transport
import io.github.nkvas1.zales.tunnel.api.EngineConfig
import io.github.nkvas1.zales.tunnel.api.EngineResult
import io.github.nkvas1.zales.tunnel.api.FailureCode
import io.github.nkvas1.zales.tunnel.api.ProbeResult
import io.github.nkvas1.zales.tunnel.api.SocketProtector
import io.github.nkvas1.zales.tunnel.api.TunnelEngine
import io.github.nkvas1.zales.tunnel.autopilot.Uplink
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * Every row of the taxonomy that the ladder can produce is reproduced here, so
 * that a diagnosis is something the code guarantees rather than something a
 * phone in another country might one day agree with.
 */
class PathCheckTest {

    private val key = AccessKey.Vless(
        label = "home",
        endpoint = Endpoint("vpn.example.com", 443),
        uuid = "6f3b1a2c-0d4e-4f5a-9b8c-7d6e5f4a3b2c",
        flow = "xtls-rprx-vision",
        encryption = "none",
        transport = Transport.Tcp(),
        security = Security.Reality("vkvideo.ru", "PBK", "ab", "chrome", null, null),
    )

    private class Engine(private val answer: (Int) -> ProbeResult) : TunnelEngine {
        override val version: String = "test"
        override fun setProtector(protector: SocketProtector?) = EngineResult.Ok(Unit)
        override fun validate(config: EngineConfig) = EngineResult.Ok(Unit)
        override fun start(config: EngineConfig, tunFd: Int) = EngineResult.Ok(Unit)
        override fun stop() = EngineResult.Ok(Unit)
        override fun isRunning() = false
        override fun probe(configs: List<EngineConfig>, url: String, timeoutMs: Int): List<ProbeResult> =
            configs.indices.map(answer)
    }

    private fun check(
        uplink: Uplink = Uplink.READY,
        names: NameReader = NameReader { Result.success(listOf(InetAddress.getByName("203.0.113.7"))) },
        ports: PortReader = PortReader { _, _, _ -> Result.success(70L) },
        background: BackgroundVerdict = BackgroundVerdict(exempt = true, unexpectedStops = 0, detail = "ok"),
        probe: (Int) -> ProbeResult = { ProbeResult(success = true, delayMs = 120, error = null) },
    ) = PathCheck(
        engine = Engine(probe),
        uplink = { uplink },
        background = { background },
        ports = ports,
        names = names,
        environment = { Environment("1.0", "15", "SM-S928B", "v26.9.9", "wifi") },
    )

    @Test
    fun `aeroplane mode is named and nothing below it is even tried`() = runTest {
        val result = check(uplink = Uplink.AIRPLANE).run(key).last()

        assertEquals(FailureCode.NET_03, result.verdict)
        assertTrue(result.finished)
        assertTrue(
            result.steps.drop(1).all { it.outcome is StepOutcome.Skipped },
            "every later rung should be skipped: ${result.steps}",
        )
    }

    @Test
    fun `a resolver that answers with a stub has not really answered`() = runTest {
        val stub = NameReader { Result.success(listOf(InetAddress.getByName("0.0.0.0"))) }
        val result = check(names = stub).run(key).last()

        assertEquals(FailureCode.SRV_01, result.verdict)
        assertTrue("stub" in result.detail, "the report should say what came back: ${result.detail}")
    }

    @Test
    fun `a refused port and a silent port are different diagnoses`() = runTest {
        val refused = check(ports = { _, _, _ -> Result.failure(Exception("Connection refused")) })
        assertEquals(FailureCode.SRV_02, refused.run(key).last().verdict)

        val silent = check(ports = { _, _, _ -> Result.failure(Exception("connect timed out")) })
        assertEquals(FailureCode.SRV_03, silent.run(key).last().verdict)
    }

    @Test
    fun `a port that opens and then goes silent on every strategy is the filter`() = runTest {
        val result = check(probe = { ProbeResult(success = false, delayMs = 0, error = "i/o timeout") })
            .run(key)
            .last()

        assertEquals(FailureCode.DPI_01, result.verdict)
        assertTrue("XHTTP" in result.report, "the report should tell the admin what to change")
    }

    @Test
    fun `a key the far end no longer knows is not called a blockage`() = runTest {
        val result = check(probe = { ProbeResult(success = false, delayMs = 0, error = "invalid user") })
            .run(key)
            .last()

        assertEquals(FailureCode.SRV_05, result.verdict)
    }

    @Test
    fun `when everything works the check says so instead of inventing a cause`() = runTest {
        val result = check().run(key).last()

        assertNull(result.verdict)
        assertTrue(result.steps.none { it.isFailure }, "nothing should have failed: ${result.steps}")
        assertTrue("no fault found" in result.report)
    }

    @Test
    fun `a phone that has been killing the tunnel is caught even when the path is fine`() = runTest {
        val result = check(
            background = BackgroundVerdict(exempt = false, unexpectedStops = 2, detail = "battery optimisation is on"),
        ).run(key).last()

        assertEquals(FailureCode.SYS_04, result.verdict)
        assertTrue(
            result.steps.first { it.step == ProbeStep.TUNNEL_HTTP }.outcome is StepOutcome.Ok,
            "the path itself was fine and should be reported as such",
        )
    }

    @Test
    fun `the report carries no secret and no full host name`() = runTest {
        val report = check().run(key).last().report

        assertFalse("vpn.example.com" in report, "the label of the host must not travel")
        assertFalse(key.uuid in report, "the uuid must never appear")
        assertTrue("example.com" in report, "the registrable domain is what an admin needs")
        assertTrue("reality" in report && "tcp" in report, "the shape of the key must be there")
    }

    @Test
    fun `the screen is fed a step at a time rather than one final answer`() = runTest {
        val frames = check().run(key).toList()

        assertTrue(frames.size > ProbeStep.entries.size, "each rung should move the screen: ${frames.size} frames")
        assertTrue(frames.any { it.running == ProbeStep.TCP }, "the working rung should be visible")
        assertTrue(frames.last().finished)
    }
}
