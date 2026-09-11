// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.nkvas1.zales.tunnel.api.FailureCode
import io.github.nkvas1.zales.tunnel.diagnostics.Diagnosis
import io.github.nkvas1.zales.tunnel.diagnostics.StepResult
import io.github.nkvas1.zales.tunnel.service.TunnelController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Puts the report somewhere the person can paste it from. */
public fun interface ReportClipboard {
    public fun put(text: String)
}

public data class DiagnosticsUiState(
    val steps: List<StepResult> = Diagnosis.starting().steps,
    val verdict: FailureCode? = null,
    val finished: Boolean = false,
    val report: String = "",
    val reportOpen: Boolean = false,
    val reportCopied: Boolean = false,
)

/**
 * Holds the check while it runs.
 *
 * The work itself happens in the `:tunnel` process; this only watches. Leaving
 * the screen cancels the check, because a probe ladder still climbing behind a
 * screen nobody is looking at is just a phone getting warm.
 */
public class DiagnosticsViewModel(
    private val tunnel: TunnelController,
    private val clipboard: ReportClipboard,
) : ViewModel() {

    private val reportOpen = MutableStateFlow(false)
    private val copied = MutableStateFlow(false)

    public val state: StateFlow<DiagnosticsUiState> = combine(
        tunnel.diagnosis,
        reportOpen,
        copied,
    ) { diagnosis, open, wasCopied ->
        val current = diagnosis ?: Diagnosis.starting()
        DiagnosticsUiState(
            steps = current.steps,
            verdict = current.verdict,
            finished = current.finished,
            report = current.report,
            reportOpen = open,
            reportCopied = wasCopied,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DiagnosticsUiState())

    public fun start() {
        reportOpen.value = false
        copied.value = false
        tunnel.diagnose()
    }

    public fun toggleReport() {
        reportOpen.value = !reportOpen.value
    }

    public fun copyReport() {
        val text = state.value.report
        if (text.isBlank()) return
        clipboard.put(text)
        copied.value = true
    }

    public fun leave() {
        tunnel.forgetDiagnosis()
    }

    public companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        public fun factory(
            tunnel: TunnelController,
            clipboard: ReportClipboard,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DiagnosticsViewModel(tunnel, clipboard) as T
        }
    }
}
