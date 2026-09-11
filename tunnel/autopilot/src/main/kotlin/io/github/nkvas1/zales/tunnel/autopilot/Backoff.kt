// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.autopilot

import kotlin.random.Random

/**
 * How long to wait before trying again, growing but never predictable.
 *
 * Two reasons for the jitter. The polite one: when a server or a whole region
 * comes back, clients that all waited exactly four seconds arrive together and
 * knock it over again. The one that matters here: a client that retries on a
 * metronome is itself a signature, and a filter that can time our retries can
 * recognise us without looking at a single byte.
 */
public class Backoff(
    private val firstDelayMs: Long = FIRST_DELAY_MS,
    private val ceilingMs: Long = CEILING_MS,
    private val random: Random = Random.Default,
) {

    private var step = 0

    public fun reset() {
        step = 0
    }

    /**
     * The next wait: somewhere in the upper half of an exponentially growing
     * window. Half rather than full jitter, because a retry that can land at
     * nearly zero delay is no backoff at all.
     */
    public fun nextDelayMs(): Long {
        val window = (firstDelayMs shl step.coerceAtMost(MAX_SHIFT)).coerceAtMost(ceilingMs)
        if (step < MAX_SHIFT) step++
        val half = window / 2
        return half + random.nextLong(half + 1)
    }

    public companion object {
        /** Short enough that a passing glitch costs nothing noticeable. */
        public const val FIRST_DELAY_MS: Long = 2_000

        /** A phone in a dead zone should not wake its radio more than this. */
        public const val CEILING_MS: Long = 120_000

        private const val MAX_SHIFT = 6
    }
}
