// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.nkvas1.zales.storage.KeyRepository
import io.github.nkvas1.zales.tunnel.api.TunnelState
import io.github.nkvas1.zales.tunnel.service.TunnelController
import io.github.nkvas1.zales.voice.Mood
import io.github.nkvas1.zales.voice.SayingContext
import io.github.nkvas1.zales.voice.SayingVoice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Everything the home screen draws, and nothing it does not. */
public data class HomeUiState(
    val tunnel: TunnelState = TunnelState.Idle,
    /** The engraved plate in the corner: version and, once open, the delay. */
    val plate: String = "",
    val saying: String? = null,
    val canSwitch: Boolean = false,
    val pulse: Float = 0f,
    val gaze: Float = 0f,
    /** The one sentence of teaching, while it is on screen. */
    val hint: Boolean = false,
)

/** Something the screen cannot do by itself and must ask an Activity for. */
public sealed interface HomeEvent {
    /** Android's own VPN consent dialog. Shown once, by the system, not by us. */
    public data object AskVpnConsent : HomeEvent

    public data object OpenKeys : HomeEvent
}

public class HomeViewModel(
    private val tunnel: TunnelController,
    private val keys: KeyRepository,
    voice: SayingVoice,
    private val versionName: String,
    private val hints: HintMemory = ForgetfulHintMemory(),
    private val random: Random = Random.Default,
) : ViewModel() {

    private val hasKey = MutableStateFlow(false)
    private val gaze = MutableStateFlow(0f)
    private val hint = MutableStateFlow(false)
    private var hintJob: Job? = null
    private val events = MutableStateFlow<HomeEvent?>(null)

    public val event: StateFlow<HomeEvent?> = events

    private val mood = tunnel.observe().map { state -> state.toMood() }.distinctUntilChanged()

    public val state: StateFlow<HomeUiState> = combine(
        tunnel.observe(),
        hasKey,
        voice.stream(mood),
        gaze,
        hint,
    ) { tunnelState, keyPresent, saying, gazeValue, hintVisible ->
        HomeUiState(
            tunnel = tunnelState,
            plate = plateFor(tunnelState),
            saying = saying?.text,
            canSwitch = keyPresent || tunnelState !is TunnelState.Idle,
            pulse = tunnelState.pulse(),
            gaze = gazeValue,
            hint = hintVisible,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    init {
        refreshKeys()
        watchForGaze()
    }

    public fun refreshKeys() {
        viewModelScope.launch { hasKey.value = keys.summaries().isNotEmpty() }
    }

    public fun toggle(open: Boolean) {
        if (!open) {
            tunnel.close()
            return
        }
        if (tunnel.consentIntent() != null) {
            events.value = HomeEvent.AskVpnConsent
            return
        }
        tunnel.open()
    }

    public fun onConsentGranted() {
        events.value = null
        tunnel.open()
    }

    public fun onEventHandled() {
        events.value = null
    }

    /**
     * A short tap is not a throw. Say so plainly, let it stand long enough to
     * be read without hurrying, and stop saying it once it has been learned.
     */
    public fun hint() {
        if (hints.timesShown() >= HintMemory.ENOUGH) return
        hints.recordShown()
        hintJob?.cancel()
        hintJob = viewModelScope.launch {
            hint.value = true
            delay(HINT_VISIBLE_MS)
            hint.value = false
        }
    }

    public fun openKeys() {
        events.value = HomeEvent.OpenKeys
    }

    /**
     * The gaze: twice an hour at most, only while the path is closed and nobody
     * is waiting on anything (docs/DESIGN.md §6).
     */
    private fun watchForGaze() {
        viewModelScope.launch {
            while (true) {
                delay(GAZE_INTERVAL_MS + random.nextLong(GAZE_JITTER_MS))
                if (state.value.tunnel != TunnelState.Idle) continue
                gaze.value = 1f
                delay(GAZE_DURATION_MS)
                gaze.value = 0f
            }
        }
    }

    private fun plateFor(state: TunnelState): String = when (state) {
        is TunnelState.Connected -> state.latencyMs?.let { "$versionName · $it мс" } ?: versionName
        else -> versionName
    }

    private fun TunnelState.pulse(): Float = when (this) {
        is TunnelState.Connected -> 1f
        is TunnelState.Degraded -> DEGRADED_PULSE
        else -> 0f
    }

    private fun TunnelState.toMood(): Mood = when (this) {
        TunnelState.Idle -> Mood(SayingContext.IDLE)
        TunnelState.Preparing, is TunnelState.Probing -> Mood(SayingContext.CONNECTING)
        is TunnelState.Connected -> Mood(SayingContext.CONNECTED_FRESH)
        is TunnelState.Degraded, is TunnelState.Reconnecting -> Mood(SayingContext.RECONNECTING)
        // Nothing is ever said while something is broken or being torn down.
        is TunnelState.Failed, TunnelState.Stopping -> Mood(context = null, stressful = true)
    }

    public companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val GAZE_INTERVAL_MS = 240_000L
        private const val GAZE_JITTER_MS = 180_000L
        private const val GAZE_DURATION_MS = 1_200L
        private const val DEGRADED_PULSE = 0.3f

        /** Long enough for an unhurried reader; short enough not to become furniture. */
        private const val HINT_VISIBLE_MS = 6_000L

        public fun factory(
            tunnel: TunnelController,
            keys: KeyRepository,
            voice: SayingVoice,
            versionName: String,
            hints: HintMemory,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(tunnel, keys, voice, versionName, hints) as T
        }
    }
}
