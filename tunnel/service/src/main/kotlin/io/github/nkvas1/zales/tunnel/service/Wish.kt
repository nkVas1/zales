// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.service

import android.content.Context

/**
 * What the person last asked for, as opposed to what is happening.
 *
 * The difference matters after a reboot and after the system kills the tunnel:
 * the state is gone, but the wish is not, and the wish is what should be
 * honoured. Written only when a person acts, never when the tunnel changes its
 * own mind.
 */
public class Wish(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    public var wantsOpen: Boolean
        get() = preferences.getBoolean(KEY, false)
        set(value) {
            preferences.edit().putBoolean(KEY, value).apply()
        }

    private companion object {
        const val FILE = "zales.wish"
        const val KEY = "wants_open"
    }
}
