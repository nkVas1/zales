// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.github.nkvas1.zales.common.ZalesLog
import io.github.nkvas1.zales.feature.diagnostics.ReportClipboard

/**
 * Puts the technical report on the clipboard.
 *
 * Marked as ordinary text and nothing more: the report has already been
 * scrubbed on the far side of the process boundary, so there is no sensitive
 * flag to set and nothing here that a clipboard reader could use.
 */
internal class ReportWriter(context: Context) : ReportClipboard {

    private val application = context.applicationContext

    override fun put(text: String) {
        val manager = application.getSystemService(ClipboardManager::class.java)
        if (manager == null) {
            ZalesLog.warn(ZalesLog.TAG_UI, "no clipboard on this device")
            return
        }
        manager.setPrimaryClip(ClipData.newPlainText(LABEL, text))
    }

    private companion object {
        const val LABEL = "Zales"
    }
}
