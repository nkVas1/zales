// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything a person can change, and there is deliberately very little of it.
 *
 * Each of these earns its place by being something a real person would ask for
 * out loud. A setting nobody would think to ask for is one more thing to get
 * lost in (docs/DESIGN.md §11), and this app is built for someone who gets lost
 * in interfaces.
 */
public data class Preferences(
    /**
     * Hides everything that is not the switch. For a person who wants one
     * object on the screen and no decisions at all.
     */
    val onlyTheSwitch: Boolean = false,

    /** The small living phrases in the quiet moments. Some people hate them. */
    val sayings: Boolean = true,

    /**
     * Russian sites go around the tunnel. On by default: banks and government
     * services refuse foreign addresses, and it keeps domestic bytes off the
     * session the censor is watching.
     */
    val bypassDomestic: Boolean = true,

    /** Ask GitHub, once a day at most, whether there is a newer build. */
    val checkForUpdates: Boolean = true,
)

/**
 * Where the preferences live in the interface process.
 *
 * [bypassDomestic] is the one that also concerns the tunnel; it is pushed
 * across the process boundary when it changes rather than read from a shared
 * file, because preferences shared between processes have never been reliable
 * on Android and quietly stopped being supported at all.
 */
public class ZalesSettings(context: Context) {

    private val store = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _preferences = MutableStateFlow(read())

    public val preferences: StateFlow<Preferences> = _preferences.asStateFlow()

    public fun setOnlyTheSwitch(value: Boolean): Unit = put(ONLY_SWITCH, value)
    public fun setSayings(value: Boolean): Unit = put(SAYINGS, value)
    public fun setBypassDomestic(value: Boolean): Unit = put(BYPASS_DOMESTIC, value)
    public fun setCheckForUpdates(value: Boolean): Unit = put(CHECK_UPDATES, value)

    private fun put(key: String, value: Boolean) {
        store.edit().putBoolean(key, value).apply()
        _preferences.value = read()
    }

    private fun read() = Preferences(
        onlyTheSwitch = store.getBoolean(ONLY_SWITCH, false),
        sayings = store.getBoolean(SAYINGS, true),
        bypassDomestic = store.getBoolean(BYPASS_DOMESTIC, true),
        checkForUpdates = store.getBoolean(CHECK_UPDATES, true),
    )

    private companion object {
        const val FILE = "zales.settings"
        const val ONLY_SWITCH = "only_the_switch"
        const val SAYINGS = "sayings"
        const val BYPASS_DOMESTIC = "bypass_domestic"
        const val CHECK_UPDATES = "check_updates"
    }
}
