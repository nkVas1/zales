// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.parsing

import io.github.nkvas1.zales.model.Endpoint

/**
 * A deliberately tolerant URI splitter.
 *
 * `java.net.URI` rejects what real keys contain: unencoded Cyrillic labels,
 * spaces in fragments, raw base64 with `+` and `/`. This only cuts the string
 * into parts; decoding is left to the protocol parsers, which know what each
 * part is allowed to hold.
 */
internal class LinkUri private constructor(
    val link: String,
    val scheme: String,
    /** Still percent-encoded. */
    val userInfo: String?,
    val host: String,
    val port: Int?,
    val path: String,
    /** Keys and values percent-decoded; `+` is kept literally, it is not a space here. */
    val query: Map<String, String>,
    /** Still percent-encoded. */
    val fragment: String?,
) {
    fun endpoint(): Endpoint {
        val validPort = port ?: incomplete("$scheme link has no port")
        if (host.isBlank()) incomplete("$scheme link has no host")
        return Endpoint(host, validPort)
    }

    fun label(fallback: Endpoint): String =
        fragment?.let(Percent::decodeUtf8)?.trim()?.ifBlank { null } ?: fallback.host

    companion object {
        fun parse(link: String): LinkUri {
            val schemeEnd = link.indexOf("://")
            if (schemeEnd <= 0) incomplete("not a link")
            val scheme = link.substring(0, schemeEnd).lowercase()
            val afterScheme = link.substring(schemeEnd + SCHEME_SEPARATOR_LENGTH)

            val fragment = afterScheme.substringAfter('#', missingDelimiterValue = "").ifEmpty { null }
            val beforeFragment = afterScheme.substringBefore('#')
            val rawQuery = beforeFragment.substringAfter('?', missingDelimiterValue = "")
            val beforeQuery = beforeFragment.substringBefore('?')

            val authority = beforeQuery.substringBefore('/')
            val path = beforeQuery.substring(authority.length)

            val at = authority.lastIndexOf('@')
            val userInfo = if (at >= 0) authority.substring(0, at) else null
            val hostPort = if (at >= 0) authority.substring(at + 1) else authority
            val (host, port) = splitHostPort(hostPort)

            return LinkUri(link, scheme, userInfo, host, port, path, parseQuery(rawQuery), fragment)
        }

        private fun splitHostPort(hostPort: String): Pair<String, Int?> {
            if (hostPort.startsWith('[')) {
                val close = hostPort.indexOf(']')
                if (close < 0) incomplete("unterminated IPv6 address")
                val host = hostPort.substring(1, close)
                val portText = hostPort.substring(close + 1).removePrefix(":")
                return host to parsePort(portText)
            }
            val colon = hostPort.lastIndexOf(':')
            return if (colon < 0) {
                hostPort to null
            } else {
                hostPort.substring(0, colon) to parsePort(hostPort.substring(colon + 1))
            }
        }

        private fun parsePort(text: String): Int? {
            if (text.isEmpty()) return null
            val port = text.toIntOrNull() ?: incomplete("port '$text' is not a number")
            if (port !in 1..MAX_PORT) incomplete("port $port is out of range")
            return port
        }

        private fun parseQuery(raw: String): Map<String, String> {
            if (raw.isEmpty()) return emptyMap()
            return raw.split('&')
                .filter { it.isNotEmpty() }
                .associate { pair ->
                    val key = Percent.decodeUtf8(pair.substringBefore('='))
                    val value = Percent.decodeUtf8(pair.substringAfter('=', missingDelimiterValue = ""))
                    key to value
                }
        }

        private const val SCHEME_SEPARATOR_LENGTH = 3
        private const val MAX_PORT = 65_535
    }
}
