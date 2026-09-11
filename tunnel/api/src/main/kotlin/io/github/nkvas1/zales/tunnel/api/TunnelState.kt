// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.api

/**
 * The life of the tunnel, as the UI sees it. See docs/ARCHITECTURE.md §5.
 *
 * Every state carries exactly the data that makes sense in it — a [Connected]
 * knows its strategy and latency, a [Failed] carries a taxonomy entry rather
 * than an error string. Timestamps are epoch milliseconds.
 */
public sealed interface TunnelState {

    /** Closed. Nothing runs. */
    public data object Idle : TunnelState

    /** Waiting for VPN permission or for the TUN interface to come up. */
    public data object Preparing : TunnelState

    /** Racing connection strategies; [attempt] counts from 1. */
    public data class Probing(
        val attempt: Int,
        val strategy: StrategyId?,
    ) : TunnelState

    /** The path is open and proven by a real request through it. */
    public data class Connected(
        val sinceEpochMs: Long,
        val strategy: StrategyId,
        val latencyMs: Int?,
        val traffic: Traffic,
    ) : TunnelState

    /**
     * Still connected, but the watchdog saw a stall or collapse. Not a failure:
     * the switch stays thrown and the UI says "the path narrowed".
     */
    public data class Degraded(
        val sinceEpochMs: Long,
        val strategy: StrategyId,
        val reason: DegradeReason,
        val traffic: Traffic,
    ) : TunnelState

    /** The network changed or a strategy died; a new one is being raced. */
    public data class Reconnecting(val attempt: Int) : TunnelState

    /** Out of options. [failure] maps to one sentence and one action. */
    public data class Failed(val failure: TunnelFailure) : TunnelState

    /** Tearing down. */
    public data object Stopping : TunnelState
}

/** Whether the switch should read as thrown. */
public val TunnelState.isEngaged: Boolean
    get() = when (this) {
        TunnelState.Idle, TunnelState.Stopping, is TunnelState.Failed -> false
        TunnelState.Preparing, is TunnelState.Probing, is TunnelState.Connected,
        is TunnelState.Degraded, is TunnelState.Reconnecting,
        -> true
    }

/** Bytes that crossed the tunnel since it opened. */
public data class Traffic(val uploadedBytes: Long, val downloadedBytes: Long) {
    public companion object {
        public val Zero: Traffic = Traffic(0, 0)
    }
}

public enum class DegradeReason {
    /** Bytes stopped moving while the socket stayed open — the TSPU freeze. */
    STALLED,

    /** Throughput collapsed far below its recent average. */
    THROTTLED,

    /** Active probes through the tunnel stopped answering. */
    PROBES_FAILING,
}

/** Stable identifier of a connection strategy, e.g. `s1+s2+s6`. */
@JvmInline
public value class StrategyId(public val value: String) {
    init {
        require(value.isNotBlank()) { "strategy id must not be blank" }
    }

    override fun toString(): String = value
}
