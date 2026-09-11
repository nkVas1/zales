// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.autopilot

/** Bytes through the tunnel at a moment in time. */
public data class TrafficSample(val uploadedBytes: Long, val downloadedBytes: Long, val atMs: Long)

public enum class Verdict {
    /** Traffic is moving, or nothing is being asked of the tunnel. */
    HEALTHY,

    /** Something is off. Worth an active probe, not worth telling anyone yet. */
    SUSPICIOUS,

    /** The path is dead in the way Russian filtering kills it: silently. */
    STALLED,
}

/**
 * Watches for the failure that does not announce itself.
 *
 * Russian DPI no longer resets connections; it freezes them. After a dozen or
 * two kilobytes the packets simply stop arriving, the socket stays open, and a
 * client that waits for an error waits for ever (docs/CONNECTIVITY.md §1).
 *
 * So the signature to look for is upload continuing while download stops. This
 * class is pure arithmetic over counters — no clock, no I/O — which is what
 * lets the whole condition be tested instead of hoped for.
 */
public class Watchdog(
    private val suspicionAfterMs: Long = SUSPICION_AFTER_MS,
    private val stallAfterMs: Long = STALL_AFTER_MS,
    private val minimumUpload: Long = MINIMUM_UPLOAD_BYTES,
) {
    private var previous: TrafficSample? = null
    private var silentSinceMs: Long? = null

    public fun reset() {
        previous = null
        silentSinceMs = null
    }

    public fun observe(sample: TrafficSample): Verdict {
        val last = previous
        previous = sample
        if (last == null) return Verdict.HEALTHY

        val sent = sample.uploadedBytes - last.uploadedBytes
        val received = sample.downloadedBytes - last.downloadedBytes

        // Nothing is being asked of the tunnel: silence here means a quiet phone,
        // not a dead path.
        if (sent < minimumUpload) {
            silentSinceMs = null
            return Verdict.HEALTHY
        }
        if (received > 0) {
            silentSinceMs = null
            return Verdict.HEALTHY
        }

        val since = silentSinceMs ?: sample.atMs.also { silentSinceMs = it }
        val silentFor = sample.atMs - since
        return when {
            silentFor >= stallAfterMs -> Verdict.STALLED
            silentFor >= suspicionAfterMs -> Verdict.SUSPICIOUS
            else -> Verdict.HEALTHY
        }
    }

    public companion object {
        /** Long enough that a slow page load is not mistaken for a dead path. */
        public const val SUSPICION_AFTER_MS: Long = 6_000

        /** By here an honest connection would have said something. */
        public const val STALL_AFTER_MS: Long = 12_000

        /** Below this, the phone is idle rather than being ignored. */
        public const val MINIMUM_UPLOAD_BYTES: Long = 1_024
    }
}
