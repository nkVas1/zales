// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales

import android.app.Application
import io.github.nkvas1.zales.common.ZalesLog

public class ZalesApp : Application() {

    /**
     * Built lazily: the tunnel process loads this class too, and it has no use
     * for the interface's objects.
     */
    public val container: ZalesContainer by lazy { ZalesContainer(this) }

    override fun onCreate() {
        super.onCreate()
        ZalesLog.info(ZalesLog.TAG_UI, "Zales ${BuildConfig.VERSION_NAME} starting")
    }
}
