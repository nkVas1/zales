// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.parsing

import io.github.nkvas1.zales.model.AccessKey

/**
 * Turns whatever a person pasted into access keys.
 *
 * Built for real messages, not clean fixtures: a key inside a sentence, a key
 * with a trailing full stop, several keys in one base64 subscription, a bare
 * `https://` link to an Outline config. It never throws — every input yields a
 * [ParseResult].
 */
public object KeyParser {

    public fun parse(input: String): ParseResult {
        val text = input.trim()
        if (text.isEmpty()) {
            return ParseResult.Failure(ParseFailure.EMPTY, "input is empty")
        }
        return try {
            parseText(text)
        } catch (@Suppress("TooGenericExceptionCaught") unexpected: RuntimeException) {
            // A parser bug must surface as "could not read the key", never as a crash.
            ParseResult.Failure(ParseFailure.UNRECOGNIZED, "parser error: ${unexpected::class.simpleName}")
        }
    }

    /**
     * Parses a document fetched from a dynamic Outline key or a subscription URL:
     * a JSON Shadowsocks struct, a list of links, or base64 of either.
     */
    public fun parseRemoteDocument(body: String): ParseResult {
        val text = body.trim()
        if (text.isEmpty()) {
            return ParseResult.Failure(ParseFailure.INCOMPLETE, "remote document is empty")
        }
        return try {
            OutlineDocumentParser.parse(text) ?: parseText(text)
        } catch (@Suppress("TooGenericExceptionCaught") unexpected: RuntimeException) {
            ParseResult.Failure(ParseFailure.UNRECOGNIZED, "parser error: ${unexpected::class.simpleName}")
        }
    }

    private fun parseText(text: String): ParseResult {
        val links = LinkExtractor.links(text)
        if (links.isNotEmpty()) {
            return parseLinks(links)
        }
        LinkExtractor.bareHttpsUrl(text)?.let { url ->
            return ParseResult.Remote(url = url, label = null)
        }
        val decoded = Base64Text.decodeOrNull(text)
        val inner = decoded?.let(LinkExtractor::links).orEmpty()
        return if (inner.isNotEmpty()) {
            parseLinks(inner)
        } else {
            ParseResult.Failure(ParseFailure.UNRECOGNIZED, "no supported link found")
        }
    }

    private fun parseLinks(links: List<String>): ParseResult {
        val keys = LinkedHashSet<AccessKey>()
        var remote: ParseResult.Remote? = null
        var firstFailure: ParseResult.Failure? = null

        for (link in links) {
            when (val result = parseLink(link)) {
                is LinkOutcome.Key -> keys += result.key
                is LinkOutcome.Remote -> if (remote == null) remote = ParseResult.Remote(result.url, result.label)
                is LinkOutcome.Rejected -> if (firstFailure == null) firstFailure = result.failure
            }
        }

        return when {
            keys.isNotEmpty() -> ParseResult.Keys(keys.toList())
            remote != null -> remote
            else -> firstFailure ?: ParseResult.Failure(ParseFailure.UNRECOGNIZED, "no supported link found")
        }
    }

    private fun parseLink(link: String): LinkOutcome = try {
        val uri = LinkUri.parse(link)
        when (uri.scheme) {
            "vless" -> LinkOutcome.Key(VlessLinkParser.parse(uri))
            "ss" -> LinkOutcome.Key(ShadowsocksLinkParser.parse(uri))
            "trojan" -> LinkOutcome.Key(TrojanLinkParser.parse(uri))
            "vmess" -> LinkOutcome.Key(VmessLinkParser.parse(link))
            "ssconf" -> uri.toRemote()
            else -> LinkOutcome.Rejected(
                ParseResult.Failure(ParseFailure.UNSUPPORTED_PROTOCOL, "scheme '${uri.scheme}'"),
            )
        }
    } catch (rejection: KeyRejection) {
        LinkOutcome.Rejected(ParseResult.Failure(rejection.failure, rejection.detail))
    } catch (invalid: IllegalArgumentException) {
        LinkOutcome.Rejected(ParseResult.Failure(ParseFailure.INCOMPLETE, invalid.message ?: "invalid value"))
    }

    private fun LinkUri.toRemote(): LinkOutcome {
        val target = link.substringAfter("://").substringBefore('#')
        if (target.isBlank()) {
            throw KeyRejection(ParseFailure.INCOMPLETE, "ssconf link has no address")
        }
        return LinkOutcome.Remote(
            url = "https://$target",
            label = fragment?.let(Percent::decodeUtf8)?.trim()?.ifBlank { null }
        )
    }

    private sealed interface LinkOutcome {
        data class Key(val key: AccessKey) : LinkOutcome
        data class Remote(val url: String, val label: String?) : LinkOutcome
        data class Rejected(val failure: ParseResult.Failure) : LinkOutcome
    }
}

public sealed interface ParseResult {
    /** One or more keys; several when a subscription was pasted. Never empty. */
    public data class Keys(val keys: List<AccessKey>) : ParseResult

    /** The configuration lives at [url] and must be fetched, then passed to [KeyParser.parseRemoteDocument]. */
    public data class Remote(val url: String, val label: String?) : ParseResult

    /** [detail] is English technical text for the report, free of secrets. */
    public data class Failure(val reason: ParseFailure, val detail: String) : ParseResult
}

public enum class ParseFailure {
    /** Nothing was pasted. */
    EMPTY,

    /** Nothing recognisable as a key. Maps to KEY-01. */
    UNRECOGNIZED,

    /** A known format with required parts missing or malformed. Maps to KEY-02. */
    INCOMPLETE,

    /** A real key this version cannot use. Maps to KEY-04. */
    UNSUPPORTED_PROTOCOL,
}

/** Internal control flow for rejecting a link with a precise reason. */
internal class KeyRejection(val failure: ParseFailure, val detail: String) : RuntimeException(detail)

internal fun incomplete(detail: String): Nothing = throw KeyRejection(ParseFailure.INCOMPLETE, detail)

internal fun unsupported(detail: String): Nothing = throw KeyRejection(ParseFailure.UNSUPPORTED_PROTOCOL, detail)
