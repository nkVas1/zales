// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.autopilot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The freeze is the failure that does not announce itself, so these tests are
 * the only place its signature is pinned down.
 */
class WatchdogTest {

    private val watchdog = Watchdog()

    private fun sample(up: Long, down: Long, atMs: Long) = TrafficSample(up, down, atMs)

    @Test
    fun `a quiet phone is not a dead tunnel`() {
        watchdog.observe(sample(0, 0, 0))
        repeat(10) { step ->
            val at = (step + 1) * 2_000L
            assertEquals(Verdict.HEALTHY, watchdog.observe(sample(0, 0, at)), "idle at $at")
        }
    }

    @Test
    fun `traffic flowing both ways is healthy`() {
        watchdog.observe(sample(0, 0, 0))
        assertEquals(Verdict.HEALTHY, watchdog.observe(sample(50_000, 900_000, 2_000)))
        assertEquals(Verdict.HEALTHY, watchdog.observe(sample(90_000, 1_800_000, 4_000)))
    }

    @Test
    fun `sending into silence becomes suspicion and then a stall`() {
        watchdog.observe(sample(0, 0, 0))
        // The phone keeps asking; nothing comes back. This is the TSPU freeze.
        assertEquals(Verdict.HEALTHY, watchdog.observe(sample(20_000, 500_000, 2_000)))
        assertEquals(Verdict.HEALTHY, watchdog.observe(sample(40_000, 500_000, 4_000)))
        assertEquals(Verdict.HEALTHY, watchdog.observe(sample(60_000, 500_000, 8_000)))
        assertEquals(Verdict.SUSPICIOUS, watchdog.observe(sample(80_000, 500_000, 11_000)))
        assertEquals(Verdict.STALLED, watchdog.observe(sample(100_000, 500_000, 17_000)))
    }

    @Test
    fun `one late answer clears the suspicion`() {
        watchdog.observe(sample(0, 0, 0))
        watchdog.observe(sample(20_000, 100, 2_000))
        // The first silent sample only starts the clock.
        assertEquals(Verdict.HEALTHY, watchdog.observe(sample(40_000, 100, 4_000)))
        assertEquals(Verdict.SUSPICIOUS, watchdog.observe(sample(60_000, 100, 11_000)))
        assertEquals(Verdict.HEALTHY, watchdog.observe(sample(80_000, 40_000, 13_000)))
        // …and the clock starts again from scratch, not from where it left off.
        assertEquals(Verdict.HEALTHY, watchdog.observe(sample(100_000, 40_000, 17_000)))
    }
}
