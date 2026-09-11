// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.design.thicket

/** How the thicket should look right now. */
public data class ThicketState(
    /** 0 closed thicket, 1 open clearing. */
    val open: Float,
    /** Live traffic, 0..1, rippling the edges of the path. */
    val pulse: Float = 0f,
    /**
     * Whether the forest breathes at all. False while a failure is on screen:
     * the atmosphere goes still when someone needs to read (docs/DESIGN.md §12).
     */
    val alive: Boolean = true,
    /** The rare pair of points in the depths, 0..1. */
    val gaze: Float = 0f,
)
