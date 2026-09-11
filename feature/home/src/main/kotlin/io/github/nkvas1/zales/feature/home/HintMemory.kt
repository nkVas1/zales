// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.feature.home

/**
 * Whether the one piece of teaching this app does has already been done.
 *
 * Kept behind an interface so the view model never holds a Context, and so the
 * rule — say it, at most, a very few times, then never again — lives in one
 * readable place instead of being spread through the screen.
 */
public interface HintMemory {
    public fun timesShown(): Int
    public fun recordShown()

    public companion object {
        /**
         * Three times, not once. A person who put the phone down mid-thought
         * deserves the sentence again; a person who now knows it does not.
         */
        public const val ENOUGH: Int = 3
    }
}

/** Remembers nothing, for previews and tests. */
public class ForgetfulHintMemory : HintMemory {
    private var count = 0
    override fun timesShown(): Int = count
    override fun recordShown() {
        count++
    }
}
