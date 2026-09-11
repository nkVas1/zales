// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.diagnostics

import io.github.nkvas1.zales.tunnel.api.FailureCode
import java.net.InetAddress

/**
 * Turns what the network actually did into one entry of the taxonomy.
 *
 * Kept as pure functions over strings and addresses so that every row of
 * docs/DIAGNOSTICS.md §2 can be reproduced in a test instead of being hoped
 * for on a phone in a different country.
 */
public object Verdicts {

    /**
     * Why a TCP connection did not happen.
     *
     * Refused and timed out mean opposite things and deserve opposite
     * sentences: something answered and said no, or nothing answered at all.
     */
    public fun forConnect(error: String?): FailureCode {
        val text = error.orEmpty().lowercase()
        return when {
            REFUSED_WORDS.any { it in text } -> FailureCode.SRV_02
            else -> FailureCode.SRV_03
        }
    }

    /**
     * Why the transport or the authorisation failed, once a socket had already
     * reached the port.
     *
     * This is the interesting one. If the door opens and then the conversation
     * dies of silence, that silence is the filter: the far end is up — the TCP
     * step proved it — and something in between is deciding how it goes.
     */
    public fun forHandshake(error: String?): FailureCode {
        val text = error.orEmpty().lowercase()
        return when {
            CERTIFICATE_WORDS.any { it in text } -> FailureCode.SRV_06
            AUTH_WORDS.any { it in text } -> FailureCode.SRV_05
            RESET_WORDS.any { it in text } -> FailureCode.DPI_02
            SILENCE_WORDS.any { it in text } -> FailureCode.DPI_01
            REFUSED_WORDS.any { it in text } -> FailureCode.SRV_02
            else -> FailureCode.SRV_04
        }
    }

    /**
     * Whether an address is a believable answer for a public host name.
     *
     * Russian resolvers answer blocked names with a stub — the unspecified
     * address, a loopback, or the provider's own block page on a private
     * range. The name resolved, so nothing looks broken; it simply leads
     * nowhere. That is a poisoned answer, not a working one.
     */
    public fun isStubAnswer(address: InetAddress): Boolean =
        address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress

    private val REFUSED_WORDS = listOf("refused", "unreachable", "econnrefused", "no route")
    private val RESET_WORDS = listOf("reset", "econnreset", "broken pipe", "eof")
    private val SILENCE_WORDS = listOf("timeout", "timed out", "deadline exceeded", "i/o timeout")
    private val AUTH_WORDS = listOf("unauthorized", "invalid user", "not authorized", "rejected", "auth")
    private val CERTIFICATE_WORDS = listOf("certificate", "x509", "tls: bad", "unknown authority", "handshake failure")
}
