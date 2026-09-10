// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales

import android.app.Application
import io.github.nkvas1.zales.common.ZalesLog

class ZalesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ZalesLog.info(ZalesLog.TAG_UI, "Zales ${BuildConfig.VERSION_NAME} starting")
    }
}
