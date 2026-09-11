// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.parsing

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Percent-decoding without `URLDecoder`'s habit of turning `+` into a space. */
internal object Percent {

    fun decodeUtf8(text: String): String = decodeBytes(text).toString(Charsets.UTF_8)

    /**
     * Decodes to raw bytes mapped one-to-one onto ISO-8859-1 characters, so any
     * byte value — including those that are not valid UTF-8 — round-trips.
     */
    fun decodeLatin1(text: String): String = decodeBytes(text).toString(Charsets.ISO_8859_1)

    private fun decodeBytes(text: String): ByteArray {
        if ('%' !in text) return text.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val hi = if (c == '%' && i + 2 < text.length + 0 && i + 2 <= text.lastIndex) hex(text[i + 1]) else -1
            val lo = if (hi >= 0) hex(text[i + 2]) else -1
            if (hi >= 0 && lo >= 0) {
                out.write((hi shl HEX_SHIFT) or lo)
                i += ESCAPE_LENGTH
            } else {
                // A lone '%' is kept as-is: real labels contain "100%".
                val bytes = c.toString().toByteArray(Charsets.UTF_8)
                out.write(bytes, 0, bytes.size)
                i += 1
            }
        }
        return out.toByteArray()
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + DECIMAL_DIGITS
        in 'A'..'F' -> c - 'A' + DECIMAL_DIGITS
        else -> -1
    }

    private const val HEX_SHIFT = 4
    private const val ESCAPE_LENGTH = 3
    private const val DECIMAL_DIGITS = 10
}

/** Base64 as it appears in keys: standard or URL-safe, padded or not, with stray whitespace. */
internal object Base64Text {

    private val alphabet = Regex("^[A-Za-z0-9+/=_-]+$")

    /** Decodes and requires the result to be valid UTF-8 text. */
    fun decodeOrNull(text: String): String? {
        val bytes = decodeBytesOrNull(text) ?: return null
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    fun decodeBytesOrNull(text: String): ByteArray? {
        val compact = text.filterNot(Char::isWhitespace)
        if (compact.length < MIN_LENGTH || !alphabet.matches(compact)) return null
        val normalized = compact.trimEnd('=').replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((BLOCK - normalized.length % BLOCK) % BLOCK)
        return try {
            Base64.getDecoder().decode(padded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private const val MIN_LENGTH = 4
    private const val BLOCK = 4
}

/** Finds links inside arbitrary pasted text. */
internal object LinkExtractor {

    // ssconf must precede ss so the longer scheme wins at the same position.
    private val link = Regex("""(?i)\b(?:vless|vmess|trojan|ssconf|ss)://[^\s"'<>«»`]+""")
    private val bareHttps = Regex("""^https://[^\s"'<>«»`]+$""", RegexOption.IGNORE_CASE)
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}"

    fun links(text: String): List<String> =
        link.findAll(text)
            .map { it.value.trimEnd { ch -> ch in TRAILING_PUNCTUATION } }
            .filter { it.substringAfter("://").isNotEmpty() }
            .toList()

    fun bareHttpsUrl(text: String): String? =
        text.trim().trimEnd { it in TRAILING_PUNCTUATION }.takeIf(bareHttps::matches)
}
