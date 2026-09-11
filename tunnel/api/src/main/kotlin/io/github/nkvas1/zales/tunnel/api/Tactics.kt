// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.api

/**
 * How to reach the same server today. One key fans out into many tactics; the
 * autopilot races them (docs/CONNECTIVITY.md §2–3).
 *
 * Every field is an intent, not engine syntax. Fields that do not apply to a
 * given key (a fingerprint on a Shadowsocks key, an XHTTP mode on WebSocket)
 * are ignored by the config builder rather than rejected, so the ladder can be
 * defined once for all key types.
 */
public data class Tactics(
    val id: StrategyId,
    /** S1 — split the TLS ClientHello so SNI-based filters miss it. */
    val fragment: Fragment? = null,
    /** S2 — override the uTLS fingerprint (`chrome`, `firefox`, `safari`, `randomized`). */
    val fingerprint: String? = null,
    /** S3 — override the XHTTP mode, typically to `packet-up` against session freezing. */
    val xhttpMode: String? = null,
    /** S4 — dial a different port on the same server. */
    val portOverride: Int? = null,
    /**
     * S5/S8 — dial this IP literal instead of resolving the host, keeping the
     * original name for TLS SNI and HTTP Host. Also how address family is chosen.
     */
    val dialAddress: String? = null,
    /**
     * S6 — multiplex many streams over one connection. Off by default: in Russia
     * a densely packed session hits the freeze threshold sooner. Ignored where the
     * protocol forbids it (Vision flow, XHTTP).
     */
    val multiplex: Boolean = false,
) {
    public companion object {
        /** The key exactly as written. */
        public val Baseline: Tactics = Tactics(StrategyId("s0"))
    }
}

/** Parameters for splitting the first bytes of a connection. */
public data class Fragment(
    /** `tlshello` fragments only the ClientHello, leaving the rest untouched. */
    val packets: String = "tlshello",
    /** Piece length range in bytes. */
    val length: String = "10-20",
    /** Pause between pieces in milliseconds. */
    val interval: String = "10-100",
)

/** What goes through the tunnel and what does not (docs/CONNECTIVITY.md §6–7). */
public data class RoutingPolicy(
    /**
     * Send Russian sites directly. Banks and government services often refuse
     * foreign IPs, and it keeps domestic bytes off the tunnel session.
     */
    val bypassDomestic: Boolean = true,
    val domesticDomainSuffixes: List<String> = DomesticDomains.Default,
    /**
     * Route IPv6. Off by default: apps are handed IPv4 answers only and stray
     * IPv6 is blocked inside the tunnel, never leaked around it.
     */
    val allowIpv6: Boolean = false,
)

/** Settings shared by the TUN interface and the engine's TUN inbound. */
public data class TunSettings(
    val mtu: Int = DEFAULT_MTU,
) {
    public companion object {
        public const val DEFAULT_MTU: Int = 1500
    }
}

public object DomesticDomains {
    /**
     * Suffixes matched as whole labels: `ru` matches `gosuslugi.ru`, never
     * `guru.com`. Punycode is required for Cyrillic TLDs.
     */
    public val Default: List<String> = listOf(
        "ru", "su", "xn--p1ai", "xn--80adxhks", "xn--d1acj3b", "moscow", "tatar",
        // Large domestic services on non-.ru names and their CDNs.
        "yandex.net", "yastatic.net", "yandex.com", "ya.ru",
        "vk.com", "vk.me", "vkuser.net", "userapi.com", "vk-cdn.net", "vkuseraudio.net",
        "mycdn.me", "okcdn.ru",
        "sber.ru", "sberbank.com", "tinkoff.ru", "tbank.ru",
        "gosuslugi.ru", "nalog.gov.ru",
        "wildberries.ru", "wbstatic.net", "ozon.ru", "ozone.ru",
        "avito.st", "mts.ru", "megafon.ru", "beeline.ru", "t2.ru",
    )
}
