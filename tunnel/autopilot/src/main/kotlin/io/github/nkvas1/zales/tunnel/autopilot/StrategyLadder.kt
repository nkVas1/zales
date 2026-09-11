// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.autopilot

import io.github.nkvas1.zales.model.AccessKey
import io.github.nkvas1.zales.model.Security
import io.github.nkvas1.zales.model.Transport
import io.github.nkvas1.zales.model.Transported
import io.github.nkvas1.zales.tunnel.api.Fragment
import io.github.nkvas1.zales.tunnel.api.StrategyId
import io.github.nkvas1.zales.tunnel.api.Tactics

/**
 * One key, many ways to reach the same server (docs/CONNECTIVITY.md §2).
 *
 * The ladder is deliberately short. Every rung has to earn its place, because
 * each one costs the person a second of waiting when everything above it fails,
 * and a rung that cannot possibly apply to this key is never built at all.
 *
 * Multiplexing is not a rung: it is off everywhere, because a densely packed
 * session reaches Russia's freeze threshold sooner than several thin ones.
 */
public class StrategyLadder {

    public fun rungsFor(key: AccessKey, resolvedAddress: String? = null): List<Tactics> = buildList {
        add(Tactics.Baseline)

        // Splitting the ClientHello is cheap and beats plain SNI filtering.
        if (key.hasTls) {
            add(Tactics(StrategyId("s1"), fragment = Fragment()))
            add(Tactics(StrategyId("s1+s2"), fragment = Fragment(), fingerprint = RANDOMIZED))
        }

        // XHTTP in packet-up mode has no long-lived session to freeze.
        if (key.transport is Transport.Xhttp) {
            add(Tactics(StrategyId("s3"), xhttpMode = PACKET_UP))
            if (key.hasTls) {
                add(Tactics(StrategyId("s1+s3"), fragment = Fragment(), xhttpMode = PACKET_UP))
            }
        }

        // Dialling the address we already resolved sidesteps a poisoned lookup.
        if (resolvedAddress != null && resolvedAddress != key.endpoint.host) {
            add(Tactics(StrategyId("s5"), dialAddress = resolvedAddress))
            if (key.hasTls) {
                add(Tactics(StrategyId("s1+s5"), fragment = Fragment(), dialAddress = resolvedAddress))
            }
        }

        // A different port only helps behind a CDN, so it goes last and stays short.
        if (key.endpoint.port == HTTPS_PORT) {
            ALTERNATE_PORTS.forEach { port ->
                add(Tactics(StrategyId("s4:$port"), portOverride = port))
            }
        }
    }

    private val AccessKey.transport: Transport?
        get() = (this as? Transported)?.transport

    private val AccessKey.hasTls: Boolean
        get() = (this as? Transported)?.security.let { it is Security.Tls || it is Security.Reality }

    public companion object {
        /** A fingerprint that is different every handshake, so there is nothing to match. */
        public const val RANDOMIZED: String = "randomized"
        public const val PACKET_UP: String = "packet-up"

        private const val HTTPS_PORT = 443

        /** Ports a CDN in front of the server is likely to be listening on. */
        private val ALTERNATE_PORTS = listOf(8443, 2053, 2083)
    }
}
