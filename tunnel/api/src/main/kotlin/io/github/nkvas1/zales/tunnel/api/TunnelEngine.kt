// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.api

/**
 * The proxy core, behind a boundary the rest of the app can rely on.
 *
 * Nothing above this interface knows it is Xray. Adding a second engine means
 * adding a module, not rewriting the app (docs/adr/0001-xray-core-only.md).
 *
 * Implementations are not required to be thread-safe; the tunnel service
 * serialises calls.
 */
/** What libXray's `pingBatch` accepts in one call. */
public const val DEFAULT_PROBE_BATCH: Int = 5

public interface TunnelEngine {

    /** Human-readable core version for the technical report, e.g. `Xray 26.9.9`. */
    public val version: String

    /**
     * Installs the socket protector. Every socket the core opens to reach the
     * server is passed through it, so that traffic bypasses the tunnel.
     * Pass `null` when the VPN interface goes away.
     */
    public fun setProtector(protector: SocketProtector?): EngineResult<Unit>

    /** Checks that [config] is accepted by the core, without starting anything. */
    public fun validate(config: EngineConfig): EngineResult<Unit>

    /**
     * Starts the core on the given TUN descriptor. The caller keeps ownership of
     * the descriptor and must keep it open until [stop] returns.
     */
    public fun start(config: EngineConfig, tunFd: Int): EngineResult<Unit>

    /** Stops the core. Idempotent. */
    public fun stop(): EngineResult<Unit>

    /** Whether the core reports itself as running. */
    public fun isRunning(): Boolean

    /**
     * Measures real round-trip latency of each config by issuing an HTTP request
     * through it, without touching the TUN. Used by the strategy race.
     */
    public fun probe(configs: List<EngineConfig>, url: String, timeoutMs: Int): List<ProbeResult>

    /**
     * How many configurations may be measured in one call.
     *
     * Not a suggestion: a batch over the limit is rejected whole, so a caller
     * that ignores this measures nothing at all and cannot tell why.
     */
    public val probeBatchLimit: Int
        get() = DEFAULT_PROBE_BATCH
}

/** A complete, engine-specific configuration document. Contains secrets. */
@JvmInline
public value class EngineConfig(public val document: String) {
    override fun toString(): String = "EngineConfig(<${document.length} chars hidden>)"
}

public fun interface SocketProtector {
    /** Returns `false` if the socket could not be excluded from the VPN. */
    public fun protect(fd: Int): Boolean
}

public sealed interface EngineResult<out T> {
    public data class Ok<T>(val value: T) : EngineResult<T>

    /**
     * [message] is the core's own error text in English. It may quote addresses,
     * so it passes through redaction before it reaches any report.
     *
     * [nativeUnavailable] means the native library could not be loaded on this
     * device at all — the build does not fit it (INT-02), not a runtime failure.
     */
    public data class Error(val message: String, val nativeUnavailable: Boolean = false) : EngineResult<Nothing>
}

public data class ProbeResult(
    val success: Boolean,
    /** Round trip in milliseconds; meaningful only when [success]. */
    val delayMs: Long,
    val error: String?,
)
