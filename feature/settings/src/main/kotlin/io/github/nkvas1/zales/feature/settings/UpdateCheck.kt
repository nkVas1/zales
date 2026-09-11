// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.feature.settings

import io.github.nkvas1.zales.common.ZalesLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** What the last look at the releases page found. */
public sealed interface UpdateState {
    public data object Unknown : UpdateState
    public data object Looking : UpdateState
    public data object UpToDate : UpdateState
    public data class Newer(val version: String, val page: String) : UpdateState

    /** Could not ask. Says nothing about whether there is an update. */
    public data object Unreachable : UpdateState
}

/**
 * Asks GitHub whether there is a newer build.
 *
 * The one address hard-coded into the app, and the only request it ever makes
 * that is not the person's own traffic. It carries nothing: no identifier, no
 * version, no counter — the answer is the same for everybody who asks, which is
 * the point.
 *
 * Most people will never use this: the recommended path is Obtainium, which
 * watches the same page and installs by itself (docs/INSTALL.md).
 */
public class UpdateCheck(
    private val currentVersion: String,
    private val endpoint: String = RELEASES,
) {

    public suspend fun look(): UpdateState = withContext(Dispatchers.IO) {
        val body = fetch() ?: return@withContext UpdateState.Unreachable
        val release = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@withContext UpdateState.Unreachable
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull()?.removePrefix("v")
            ?: return@withContext UpdateState.Unreachable
        val page = release["html_url"]?.jsonPrimitive?.contentOrNull() ?: PAGE

        if (isNewer(tag, currentVersion)) UpdateState.Newer(tag, page) else UpdateState.UpToDate
    }

    private fun fetch(): String? = runCatching {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.inputStream.use { it.bufferedReader().readText().take(MAX_BODY) }
    }.onFailure { ZalesLog.info(ZalesLog.TAG_UI, "could not reach the releases page") }.getOrNull()

    public companion object {
        private const val RELEASES = "https://api.github.com/repos/nkVas1/zales/releases/latest"
        private const val PAGE = "https://github.com/nkVas1/zales/releases"
        private const val TIMEOUT_MS = 10_000
        private const val MAX_BODY = 64 * 1024

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Compares two dotted versions number by number.
         *
         * Deliberately not a string comparison: "1.10.0" is newer than "1.9.0"
         * and sorts before it, which is exactly the bug that tells everybody
         * they are up to date for ever.
         */
        internal fun isNewer(candidate: String, current: String): Boolean {
            val left = candidate.split('.', '-').mapNotNull { it.toIntOrNull() }
            val right = current.split('.', '-').mapNotNull { it.toIntOrNull() }
            if (left.isEmpty() || right.isEmpty()) return false
            for (index in 0 until maxOf(left.size, right.size)) {
                val a = left.getOrElse(index) { 0 }
                val b = right.getOrElse(index) { 0 }
                if (a != b) return a > b
            }
            return false
        }
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    content.takeIf { it.isNotBlank() && it != "null" }
