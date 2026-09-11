// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.autopilot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The waits are load-bearing in two directions at once — too short and the
 * retries themselves become a signature, too long and a phone sits dark in the
 * user's hand — so both ends are pinned here.
 */
class BackoffTest {

    @Test
    fun `each wait is somewhere in the upper half of its window`() {
        val backoff = Backoff(firstDelayMs = 1_000, ceilingMs = 64_000, random = Random(7))
        val windows = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L)
        windows.forEach { window ->
            val delay = backoff.nextDelayMs()
            assertTrue(delay in window / 2..window, "$delay outside ${window / 2}..$window")
        }
    }

    @Test
    fun `the wait stops growing at the ceiling`() {
        val backoff = Backoff(firstDelayMs = 1_000, ceilingMs = 4_000, random = Random(1))
        repeat(40) { backoff.nextDelayMs() }
        repeat(20) {
            assertTrue(backoff.nextDelayMs() <= 4_000, "a wait grew past the ceiling")
        }
    }

    @Test
    fun `two phones retrying together do not stay together`() {
        val one = Backoff(random = Random(1))
        val other = Backoff(random = Random(2))
        val differences = (1..8).count { one.nextDelayMs() != other.nextDelayMs() }
        assertTrue(differences >= 6, "only $differences of 8 waits differed")
    }

    @Test
    fun `a reconnect starts from the short wait again`() {
        val backoff = Backoff(firstDelayMs = 1_000, ceilingMs = 64_000, random = Random(3))
        repeat(5) { backoff.nextDelayMs() }
        backoff.reset()
        assertTrue(backoff.nextDelayMs() <= 1_000, "the first wait after a reset must be short")
    }

    @Test
    fun `the shipped defaults are the ones the docs describe`() {
        assertEquals(2_000L, Backoff.FIRST_DELAY_MS)
        assertEquals(120_000L, Backoff.CEILING_MS)
    }
}
