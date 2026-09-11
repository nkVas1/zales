// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.storage

import io.github.nkvas1.zales.model.AccessKey
import io.github.nkvas1.zales.model.KeyDescriptor
import io.github.nkvas1.zales.parsing.KeyParser
import io.github.nkvas1.zales.parsing.ParseFailure
import io.github.nkvas1.zales.parsing.ParseResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.UUID

/**
 * The only owner of access keys.
 *
 * Both processes use it: the UI process adds, lists and removes keys through
 * [KeySummary], which carries no secrets; the `:tunnel` process calls
 * [activeKey] to build a configuration. **A secret never travels above this
 * class in the UI process** (CLAUDE.md rule 5).
 *
 * The whole store is one encrypted file guarded by an OS file lock, so the two
 * processes never see a half-written state. Nothing is cached in memory: every
 * read reflects what the other process may just have written.
 */
public class KeyRepository(
    private val file: File,
    private val cipher: BlobCipher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Keys for display, active one first. */
    public suspend fun summaries(): List<KeySummary> = withContext(io) {
        val state = locked(write = false) { read() }
        state.keys
            .mapNotNull { stored ->
                stored.resolve()?.let { key ->
                    KeySummary(
                        stored.id,
                        key.describe(),
                        stored.addedAtMs,
                        stored.id == state.activeId
                    )
                }
            }
            .sortedWith(compareByDescending<KeySummary> { it.isActive }.thenByDescending { it.addedAtMs })
    }

    /**
     * Parses [text] and stores every key it contains. The first new key becomes
     * active if nothing was active yet. Remote keys must be fetched first and
     * added with [addRemote].
     */
    public suspend fun add(text: String): AddResult = withContext(io) {
        when (val parsed = KeyParser.parse(text)) {
            is ParseResult.Keys -> store(source = text.trim(), remoteUrl = null, count = parsed.keys.size)
            is ParseResult.Remote -> AddResult.NeedsFetch(parsed.url, parsed.label)
            is ParseResult.Failure -> AddResult.Rejected(parsed.reason, parsed.detail)
        }
    }

    /** Stores keys from a document fetched from [url]. Refreshing the same URL replaces its keys. */
    public suspend fun addRemote(url: String, document: String): AddResult = withContext(io) {
        when (val parsed = KeyParser.parseRemoteDocument(document)) {
            is ParseResult.Keys -> store(source = document.trim(), remoteUrl = url, count = parsed.keys.size)
            is ParseResult.Remote -> AddResult.Rejected(
                ParseFailure.UNRECOGNIZED,
                "remote document points to another remote"
            )
            is ParseResult.Failure -> AddResult.Rejected(parsed.reason, parsed.detail)
        }
    }

    public suspend fun setActive(id: String): Boolean = withContext(io) {
        locked(write = true) {
            val state = read()
            if (state.keys.none { it.id == id }) return@locked false
            write(state.copy(activeId = id))
            true
        }
    }

    public suspend fun remove(id: String): Unit = withContext(io) {
        locked(write = true) {
            val state = read()
            val remaining = state.keys.filterNot { it.id == id }
            val active = state.activeId.takeIf { it != id } ?: remaining.maxByOrNull { it.addedAtMs }?.id
            write(State(remaining, active))
        }
    }

    /** Remote URLs whose keys may need refreshing. */
    public suspend fun remoteSources(): List<String> = withContext(io) {
        locked(write = false) { read() }.keys.mapNotNull { it.remoteUrl }.distinct()
    }

    /**
     * The active key with its secrets. **For the `:tunnel` process only.**
     * Returns `null` when no key is stored.
     */
    public suspend fun activeKey(): AccessKey? = withContext(io) {
        val state = locked(write = false) { read() }
        val active = state.keys.firstOrNull { it.id == state.activeId } ?: state.keys.maxByOrNull { it.addedAtMs }
        active?.resolve()
    }

    private fun store(source: String, remoteUrl: String?, count: Int): AddResult = locked(write = true) {
        val state = read()
        val kept = if (remoteUrl != null) state.keys.filterNot { it.remoteUrl == remoteUrl } else state.keys
        val existingSources = kept.map { it.source to it.index }.toSet()
        val now = clock()
        val added = (0 until count)
            .filterNot { (source to it) in existingSources }
            .map { index -> StoredKey(newId(), source, remoteUrl, index, now) }
        val keys = kept + added
        val activeId = state.activeId?.takeIf { id -> keys.any { it.id == id } }
            ?: added.firstOrNull()?.id
            ?: keys.firstOrNull()?.id
        write(State(keys, activeId))
        AddResult.Added(added.map { it.id }, duplicates = count - added.size)
    }

    private fun StoredKey.resolve(): AccessKey? {
        val parsed = if (remoteUrl != null) KeyParser.parseRemoteDocument(source) else KeyParser.parse(source)
        return (parsed as? ParseResult.Keys)?.keys?.getOrNull(index)
    }

    // ── File format ────────────────────────────────────────────────────

    private fun read(): State {
        if (!file.exists() || file.length() == 0L) return State(emptyList(), null)
        val plain = cipher.decrypt(file.readBytes()).toString(Charsets.UTF_8)
        val root = json.parseToJsonElement(plain).jsonObject
        val keys = root.getValue("keys").jsonArray.map { element ->
            val o = element.jsonObject
            StoredKey(
                id = o.getValue("id").jsonPrimitive.content,
                source = o.getValue("source").jsonPrimitive.content,
                remoteUrl = (o["remoteUrl"] as? JsonPrimitive)?.contentOrNull,
                index = o.getValue("index").jsonPrimitive.int,
                addedAtMs = o.getValue("addedAt").jsonPrimitive.long,
            )
        }
        return State(keys, (root["activeId"] as? JsonPrimitive)?.contentOrNull)
    }

    private fun write(state: State) {
        val document: JsonObject = buildJsonObject {
            put("version", FORMAT_VERSION)
            state.activeId?.let { put("activeId", it) }
            putJsonArray("keys") {
                state.keys.forEach { key ->
                    addJsonObject {
                        put("id", key.id)
                        put("source", key.source)
                        key.remoteUrl?.let { put("remoteUrl", it) }
                        put("index", key.index)
                        put("addedAt", key.addedAtMs)
                    }
                }
            }
        }
        val sealed = cipher.encrypt(json.encodeToString(JsonObject.serializer(), document).toByteArray(Charsets.UTF_8))
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(sealed)
        check(temp.renameTo(file) || (file.delete() && temp.renameTo(file))) { "could not replace ${file.name}" }
    }

    /** Serialises access across both processes with an advisory OS lock on a side file. */
    private inline fun <T> locked(write: Boolean, block: () -> T): T {
        file.parentFile?.mkdirs()
        RandomAccessFile(File(file.parentFile, "${file.name}.lock"), "rw").use { raf ->
            val channel: FileChannel = raf.channel
            channel.lock(0L, Long.MAX_VALUE, !write).use { return block() }
        }
    }

    private data class StoredKey(
        val id: String,
        /** The original text; re-parsed on every read so parser fixes apply to old keys. */
        val source: String,
        val remoteUrl: String?,
        val index: Int,
        val addedAtMs: Long,
    ) {
        override fun toString(): String = "StoredKey(id=$id, index=$index, <source hidden>)"
    }

    private data class State(val keys: List<StoredKey>, val activeId: String?)

    private companion object {
        const val FORMAT_VERSION = 1
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** A key as the UI may see it. */
public data class KeySummary(
    val id: String,
    val descriptor: KeyDescriptor,
    val addedAtMs: Long,
    val isActive: Boolean,
)

public sealed interface AddResult {
    /** [ids] of newly stored keys; [duplicates] were already present. */
    public data class Added(val ids: List<String>, val duplicates: Int) : AddResult

    /** The text points to a remote configuration that must be downloaded first. */
    public data class NeedsFetch(val url: String, val label: String?) : AddResult

    public data class Rejected(val reason: ParseFailure, val detail: String) : AddResult
}
