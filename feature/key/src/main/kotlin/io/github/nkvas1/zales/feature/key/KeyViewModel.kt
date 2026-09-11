// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.feature.key

import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.nkvas1.zales.common.ZalesLog
import io.github.nkvas1.zales.model.KeyDescriptor
import io.github.nkvas1.zales.model.Protocol
import io.github.nkvas1.zales.model.SecurityKind
import io.github.nkvas1.zales.model.TransportKind
import io.github.nkvas1.zales.parsing.KeyParser
import io.github.nkvas1.zales.parsing.ParseFailure
import io.github.nkvas1.zales.parsing.ParseResult
import io.github.nkvas1.zales.storage.AddResult
import io.github.nkvas1.zales.storage.KeyRepository
import io.github.nkvas1.zales.words.Words
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

public data class KeyUiState(
    val text: TextFieldValue = TextFieldValue(""),
    val clipboardLooksLikeKey: Boolean = false,
    @param:StringRes val problem: Int? = null,
    /** How many keys the last save accepted, or `null` if nothing was saved yet. */
    val acceptedCount: Int? = null,
    val busy: Boolean = false,
    val stored: List<StoredKeyView> = emptyList(),
)

/** A stored key as the screen shows it: a name, a technical line, and no secrets. */
public data class StoredKeyView(
    val id: String,
    val label: String,
    val detail: String,
    val active: Boolean,
    val insecure: Boolean,
)

/**
 * Reading the clipboard is the whole reason this screen usually needs no typing,
 * but it is also the one thing here that needs Android. Behind an interface it
 * stays out of the view model, which then holds no context at all.
 */
public fun interface ClipboardSource {
    public fun read(): String?
}

public class AndroidClipboard(context: Context) : ClipboardSource {
    private val application = context.applicationContext

    override fun read(): String? {
        val manager = application.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(application).toString().takeIf { it.isNotBlank() }
    }
}

public class KeyViewModel(
    private val keys: KeyRepository,
    private val clipboard: ClipboardSource,
) : ViewModel() {

    private val _state = MutableStateFlow(KeyUiState())
    public val state: StateFlow<KeyUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    public fun refresh() {
        viewModelScope.launch {
            val stored = keys.summaries().map { summary ->
                StoredKeyView(
                    id = summary.id,
                    label = summary.descriptor.label,
                    detail = summary.descriptor.line(),
                    active = summary.isActive,
                    insecure = summary.descriptor.skipsCertificateCheck,
                )
            }
            _state.update { it.copy(stored = stored, clipboardLooksLikeKey = clipboardHasKey()) }
        }
    }

    public fun onTextChange(value: TextFieldValue) {
        _state.update { it.copy(text = value, problem = null, acceptedCount = null) }
    }

    public fun pasteClipboard() {
        val text = clipboard.read() ?: return
        _state.update { it.copy(text = TextFieldValue(text), problem = null, acceptedCount = null) }
    }

    public fun save() {
        val text = _state.value.text.text
        if (text.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, problem = null, acceptedCount = null) }
            val result = when (val parsed = KeyParser.parse(text)) {
                is ParseResult.Remote -> fetchAndStore(parsed.url)
                else -> keys.add(text)
            }
            applyResult(result)
            refresh()
        }
    }

    public fun forget(id: String) {
        viewModelScope.launch {
            keys.remove(id)
            refresh()
        }
    }

    private suspend fun fetchAndStore(url: String): AddResult {
        val document = download(url)
            ?: return AddResult.Rejected(ParseFailure.INCOMPLETE, "remote configuration did not answer")
        return keys.addRemote(url, document)
    }

    private fun applyResult(result: AddResult) {
        when (result) {
            is AddResult.Added -> _state.update {
                it.copy(busy = false, acceptedCount = result.ids.size, text = TextFieldValue(""))
            }
            is AddResult.NeedsFetch -> _state.update {
                it.copy(busy = false, problem = R.string.key_fetch_failed)
            }
            is AddResult.Rejected -> _state.update {
                it.copy(busy = false, problem = Words.parseSentence(result.reason))
            }
        }
    }

    /**
     * Fetches a dynamic key's configuration. Kept deliberately small and strict:
     * a hard size cap, a short timeout and no certificate exceptions.
     */
    private suspend fun download(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.inputStream.use { stream ->
                stream.bufferedReader().use { reader -> reader.readText().take(MAX_DOCUMENT) }
            }
        }.onFailure { ZalesLog.warn(ZalesLog.TAG_UI, "could not fetch a dynamic key", it) }.getOrNull()
    }

    private fun clipboardHasKey(): Boolean {
        val text = clipboard.read() ?: return false
        return KeyParser.parse(text) !is ParseResult.Failure
    }

    private fun KeyDescriptor.line(): String = buildList {
        add(
            when (protocol) {
                Protocol.VLESS -> "VLESS"
                Protocol.SHADOWSOCKS -> "Outline"
                Protocol.TROJAN -> "Trojan"
                Protocol.VMESS -> "VMess"
            },
        )
        transport?.let { add(it.label()) }
        when (security) {
            SecurityKind.TLS -> add("tls")
            SecurityKind.REALITY -> add("reality")
            SecurityKind.NONE, null -> Unit
        }
        add(host)
    }.joinToString(" · ")

    private fun TransportKind.label(): String = when (this) {
        TransportKind.TCP -> "tcp"
        TransportKind.WEBSOCKET -> "ws"
        TransportKind.GRPC -> "grpc"
        TransportKind.HTTP_UPGRADE -> "httpupgrade"
        TransportKind.XHTTP -> "xhttp"
    }

    public companion object {
        private const val TIMEOUT_MS = 12_000
        private const val MAX_DOCUMENT = 256 * 1024

        public fun factory(keys: KeyRepository, clipboard: ClipboardSource): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    KeyViewModel(keys, clipboard) as T
            }
    }
}
