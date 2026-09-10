// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.nkvas1.zales.common.ZalesLog
import io.github.nkvas1.zales.design.ZalesTheme
import io.github.nkvas1.zales.ui.ShellScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ZalesLog.info(ZalesLog.TAG_UI, "MainActivity created, build ${BuildConfig.VERSION_NAME}")

        setContent {
            ZalesTheme {
                ShellScreen(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                )
            }
        }
    }
}
