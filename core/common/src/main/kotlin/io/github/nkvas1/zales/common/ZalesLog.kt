// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.common

import android.util.Log

/**
 * The only logging entry point in Zales.
 *
 * Two tags exist on purpose, one per process: `Zales` for the UI process and
 * `ZalesTunnel` for the `:tunnel` process. Filtering a live device by a single
 * tag is the difference between debugging and archaeology.
 *
 * Nothing here ever writes a key, a password or a server address. The rule is
 * enforced by review, not by the type system, so it is stated loudly:
 * **secrets never reach a log.**
 */
public object ZalesLog {

    public const val TAG_UI: String = "Zales"
    public const val TAG_TUNNEL: String = "ZalesTunnel"

    /** Verbose detail, compiled out of release builds by R8. */
    public fun debug(tag: String, message: () -> String) {
        if (Log.isLoggable(tag, Log.DEBUG)) {
            Log.d(tag, message())
        }
    }

    /** A notable event: state change, user action, lifecycle milestone. */
    public fun info(tag: String, message: String) {
        Log.i(tag, message)
    }

    /** Something unexpected that the app recovered from. */
    public fun warn(tag: String, message: String, cause: Throwable? = null) {
        Log.w(tag, message, cause)
    }

    /** Something that failed and the user will notice. */
    public fun error(tag: String, message: String, cause: Throwable? = null) {
        Log.e(tag, message, cause)
    }
}
