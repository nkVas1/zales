// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.diagnostics

import android.content.Context
import android.os.PowerManager
import io.github.nkvas1.zales.common.ZalesLog

/**
 * Keeps track of whether the phone has been quietly killing the tunnel.
 *
 * On Samsung's One UI in particular, an app the person has not opened for a few
 * days is put to sleep, and a VPN that is put to sleep simply stops — with no
 * error anywhere, because nothing failed. The only way to know it happened is
 * to notice, on the way back up, that the last thing written down was
 * "connected" and nobody ever wrote "stopped".
 *
 * Lives in the `:tunnel` process, whose death is the very thing being counted.
 */
public class Survival(context: Context) {

    private val application = context.applicationContext
    private val preferences = application.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Reads the note left by the previous life of this process and starts a
     * fresh page. Call once, as the process comes up.
     */
    public fun openLedger() {
        if (!preferences.getBoolean(KEY_RUNNING, false)) return
        val stops = preferences.getInt(KEY_STOPS, 0) + 1
        preferences.edit().putBoolean(KEY_RUNNING, false).putInt(KEY_STOPS, stops).apply()
        ZalesLog.warn(ZalesLog.TAG_TUNNEL, "the tunnel was killed while connected ($stops so far)")
    }

    /** Written the moment the path opens, so an unexplained death leaves a trace. */
    public fun markRunning() {
        preferences.edit().putBoolean(KEY_RUNNING, true).apply()
    }

    /** Written on every ending we chose ourselves, including failures. */
    public fun markStopped() {
        preferences.edit().putBoolean(KEY_RUNNING, false).apply()
    }

    /** Forgets the history, once the person has done something about it. */
    public fun forgive() {
        preferences.edit().putInt(KEY_STOPS, 0).apply()
    }

    public fun verdict(): BackgroundVerdict {
        val power = application.getSystemService(PowerManager::class.java)
        val exempt = power?.isIgnoringBatteryOptimizations(application.packageName) ?: true
        val stops = preferences.getInt(KEY_STOPS, 0)
        return BackgroundVerdict(
            exempt = exempt,
            unexpectedStops = stops,
            detail = if (exempt) "exempt from battery optimisation" else "battery optimisation is on",
        )
    }

    private companion object {
        const val FILE = "zales.survival"
        const val KEY_RUNNING = "running"
        const val KEY_STOPS = "unexpected_stops"
    }
}
