// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales

import android.content.Context
import androidx.core.content.edit
import io.github.nkvas1.zales.feature.home.HintMemory

/**
 * The one thing the app remembers about the person using it: whether they have
 * already been told how the handle works.
 *
 * Deliberately ordinary preferences and deliberately not backed up — a restored
 * phone is a good moment to be told again.
 */
internal class StoredHintMemory(context: Context) : HintMemory {

    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun timesShown(): Int = preferences.getInt(KEY, 0)

    override fun recordShown() {
        preferences.edit { putInt(KEY, timesShown() + 1) }
    }

    private companion object {
        const val FILE = "zales.learned"
        const val KEY = "throw_hint_shown"
    }
}
