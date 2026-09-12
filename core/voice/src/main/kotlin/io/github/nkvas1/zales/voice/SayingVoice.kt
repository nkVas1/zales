// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest

/**
 * The voice as the screen consumes it: a mood goes in, a line comes out, at a
 * pace that never hurries the reader.
 *
 * A line appears only after the app has been in the same state for a moment, so
 * it cannot flicker past during a quick transition; once shown it stays long
 * enough to be read, and it is replaced slowly (docs/VOICE.md §4.3).
 */
public class SayingVoice(private val picker: Lazy<SayingPicker>) {

    public constructor(picker: SayingPicker) : this(lazyOf(picker))

    /**
     * Moved off the main thread on purpose.
     *
     * The corpus is a hundred and forty lines of JSON read out of the assets,
     * and the first line is not wanted for another second and a bit — so
     * neither the read nor the parse has any business happening in the frame
     * that is trying to draw the switch.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    public fun stream(mood: Flow<Mood>): Flow<Saying?> = mood.transformLatest { current ->
        if (current.stressful || current.context == null) {
            emit(null)
            return@transformLatest
        }
        emit(null)
        delay(APPEAR_AFTER_MS)
        while (true) {
            val saying = picker.value.pick(current)
            if (saying == null) {
                emit(null)
                return@transformLatest
            }
            emit(saying)
            delay(REPLACE_EVERY_MS)
        }
    }.flowOn(Dispatchers.Default)

    public companion object {
        /** Long enough that a state passed through in a hurry never speaks. */
        public const val APPEAR_AFTER_MS: Long = 1_200

        /** Comfortably longer than it takes to read a short line twice. */
        public const val REPLACE_EVERY_MS: Long = 9_000

        /**
         * Loads the corpus, unless the phone is not reading Russian.
         *
         * The sayings are Russian folk idiom — half of them are proverbs bent
         * out of shape, and the joke is in the bending. Translated they would
         * be twee, and a VPN that is twee in a hard moment is worse than a VPN
         * that says nothing. So outside Russian the app keeps quiet, and the
         * small grey line simply never appears.
         */
        public fun fromAssets(context: Context): SayingVoice {
            if (!speaksRussian(context)) return SayingVoice(SayingPicker(Sayings(emptyList())))
            val assets = context.applicationContext.assets
            return SayingVoice(lazy { SayingPicker(assets.open(Sayings.ASSET).use(Sayings::read)) })
        }

        private fun speaksRussian(context: Context): Boolean {
            val locales = context.resources.configuration.locales
            if (locales.isEmpty) return false
            return locales.get(0).language == RUSSIAN
        }

        private const val RUSSIAN = "ru"
    }
}
