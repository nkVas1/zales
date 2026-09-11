// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.api

/**
 * A failure the user can be told about.
 *
 * [code] selects the sentence and the single action (docs/DIAGNOSTICS.md §2);
 * [detail] is English technical text for the "for whoever gave you the key"
 * report and is never shown on the main screen. It must never contain secrets.
 */
public data class TunnelFailure(
    val code: FailureCode,
    val detail: String,
)

/** The failure taxonomy. Codes are stable: they appear in shared reports. */
public enum class FailureCode(public val group: FailureGroup) {
    /** No network interface at all. */
    NET_01(FailureGroup.DEVICE),

    /** A network exists but nothing is reachable behind it (captive portal). */
    NET_02(FailureGroup.DEVICE),

    /** Airplane mode. */
    NET_03(FailureGroup.DEVICE),

    /** VPN permission was not granted. */
    SYS_01(FailureGroup.DEVICE),

    /** Another VPN app holds the tunnel. */
    SYS_02(FailureGroup.DEVICE),

    /** Samsung put the app to sleep. */
    SYS_03(FailureGroup.DEVICE),

    /** Battery optimisation killed background work. */
    SYS_04(FailureGroup.DEVICE),

    /** No parser recognised the key. */
    KEY_01(FailureGroup.KEY),

    /** Known format, required fields missing. */
    KEY_02(FailureGroup.KEY),

    /** A dynamic Outline key's config URL did not answer. */
    KEY_03(FailureGroup.KEY),

    /** Protocol not supported by this version. */
    KEY_04(FailureGroup.KEY),

    /** There is no key yet. */
    KEY_05(FailureGroup.KEY),

    /** The server's name did not resolve. */
    SRV_01(FailureGroup.SERVER),

    /** TCP connection refused. */
    SRV_02(FailureGroup.SERVER),

    /** TCP connection timed out. */
    SRV_03(FailureGroup.SERVER),

    /** Transport handshake rejected. */
    SRV_04(FailureGroup.SERVER),

    /** Proxy authentication failed — key revoked or expired. */
    SRV_05(FailureGroup.SERVER),

    /** Certificate mismatch: something impersonates the server. */
    SRV_06(FailureGroup.SERVER),

    /** Every strategy failed at the handshake. */
    DPI_01(FailureGroup.CENSORSHIP),

    /** Session freeze detected. Self-healing. */
    DPI_02(FailureGroup.CENSORSHIP),

    /** Throttled to near uselessness. Self-healing. */
    DPI_03(FailureGroup.CENSORSHIP),

    /** Works on Wi-Fi, blocked on cellular. */
    DPI_04(FailureGroup.CENSORSHIP),

    /** The core failed to start. */
    INT_01(FailureGroup.INTERNAL),

    /** The native library does not fit this device. */
    INT_02(FailureGroup.INTERNAL),

    /** The key store is unavailable. */
    INT_03(FailureGroup.INTERNAL),
    ;

    /** Whether the app heals this on its own, so no action button is shown. */
    public val isSelfHealing: Boolean
        get() = this == DPI_02 || this == DPI_03

    /** The code as printed in reports: `SRV-05`. */
    public val printable: String
        get() = name.replace('_', '-')
}

public enum class FailureGroup { DEVICE, KEY, SERVER, CENSORSHIP, INTERNAL }
