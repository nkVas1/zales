// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.nkvas1.zales.settings.Preferences
import io.github.nkvas1.zales.settings.ZalesSettings
import io.github.nkvas1.zales.tunnel.service.TunnelController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Applies changes as they are made, with no save button anywhere.
 *
 * A settings screen that has to be confirmed is a settings screen somebody
 * leaves without confirming.
 */
public class SettingsViewModel(
    private val settings: ZalesSettings,
    private val tunnel: TunnelController,
    private val updates: UpdateCheck,
) : ViewModel() {

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    public val update: StateFlow<UpdateState> = _update.asStateFlow()

    public val preferences: StateFlow<Preferences> = settings.preferences

    public fun apply(next: Preferences) {
        val current = preferences.value
        if (next.onlyTheSwitch != current.onlyTheSwitch) settings.setOnlyTheSwitch(next.onlyTheSwitch)
        if (next.sayings != current.sayings) settings.setSayings(next.sayings)
        if (next.checkForUpdates != current.checkForUpdates) settings.setCheckForUpdates(next.checkForUpdates)
        if (next.bypassDomestic != current.bypassDomestic) {
            settings.setBypassDomestic(next.bypassDomestic)
            // The tunnel decides routing, so it has to be told; it applies the
            // change under a live interface without dropping anything.
            tunnel.setBypassDomestic(next.bypassDomestic)
        }
    }

    public fun checkForUpdate() {
        if (_update.value == UpdateState.Looking) return
        viewModelScope.launch {
            _update.value = UpdateState.Looking
            _update.value = updates.look()
        }
    }

    public companion object {
        public fun factory(
            settings: ZalesSettings,
            tunnel: TunnelController,
            updates: UpdateCheck,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(settings, tunnel, updates) as T
        }
    }
}
