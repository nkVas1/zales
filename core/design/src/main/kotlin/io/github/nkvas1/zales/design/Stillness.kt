// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.design

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the person has asked the system to stop moving things.
 *
 * Honoured seriously here, because this app moves more than most: a breathing
 * forest, a drifting gaze, a switch that swings. For someone with vestibular
 * trouble that is not atmosphere, it is nausea.
 *
 * What stops: everything decorative — the thicket's drift, the gaze, the
 * breathing marks. What does not: the handle. It moves because a finger is
 * moving it, and a control that does not follow the finger is broken rather
 * than calm.
 */
public val LocalStillness: androidx.compose.runtime.ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }

public object Motion {
    /** True when decoration should hold still. */
    public val stilled: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalStillness.current
}

/**
 * Reads the system's animation scale.
 *
 * `ANIMATOR_DURATION_SCALE` at zero is what both "remove animations" in
 * accessibility settings and the developer options switch come down to, and it
 * has been readable on every version this app supports — unlike
 * `isRequestedReduceMotionEnabled`, which arrived only in API 34.
 */
public fun readStillness(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)
