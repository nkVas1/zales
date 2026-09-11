// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales

import android.content.Context
import io.github.nkvas1.zales.feature.home.HintMemory
import io.github.nkvas1.zales.settings.ZalesSettings
import io.github.nkvas1.zales.storage.KeyRepository
import io.github.nkvas1.zales.storage.KeystoreBlobCipher
import io.github.nkvas1.zales.tunnel.service.TunnelController
import io.github.nkvas1.zales.voice.SayingVoice
import java.io.File

/**
 * What the app is made of, assembled in one place.
 *
 * No framework: the graph is four objects deep and a reader can hold all of it
 * in their head, which is worth more here than any amount of generated wiring.
 *
 * The key store points at exactly the file the `:tunnel` process opens, and the
 * two coordinate through an OS file lock rather than through this object.
 */
public class ZalesContainer(context: Context) {

    private val application = context.applicationContext

    public val tunnel: TunnelController = TunnelController(application)

    public val keys: KeyRepository = KeyRepository(
        file = File(application.noBackupFilesDir, KEY_STORE_FILE),
        cipher = KeystoreBlobCipher(),
    )

    public val voice: SayingVoice = SayingVoice.fromAssets(application)

    public val settings: ZalesSettings = ZalesSettings(application)

    internal val hints: HintMemory = StoredHintMemory(application)

    private companion object {
        /** Must match ZalesVpnService: both processes open the same store. */
        const val KEY_STORE_FILE = "keys.bin"
    }
}
