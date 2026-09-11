// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The Zales palette, built on one rule: **there is no middle ground.**
 *
 * Everything further than arm's length is cold — that is the thicket, the
 * hostile network. Everything within reach is warm — that is the hut, your key
 * and your phone. A colour that sits between the two families does not exist,
 * and adding one would dissolve the whole direction.
 *
 * See docs/DESIGN.md §1 and §3.
 */
@Immutable
public data class ZalesColors(
    // ── Thicket: cold, always the background ───────────────────────
    /** Darkness the lamp does not reach. */
    val void: Color,
    /** Far plane of the forest. */
    val deep: Color,
    /** Near plane, and the surface of the clearing. */
    val thicket: Color,
    /** Cold blue-grey: silhouettes, dividers. */
    val cold: Color,
    /** The limit of what can be made out at all. */
    val breath: Color,
    /** The tone of the gaze. Barely above noise, and rare. */
    val watch: Color,
    /**
     * Rime — the only cold tone allowed to carry text. Same hue as [breath],
     * lifted until it reaches 7:1 on [void]. [breath], [cold] and [rust] fail
     * that bar on a real device and must never be used for text.
     */
    val rime: Color,

    // ── Hut: warm, objects only ────────────────────────────────────
    /** Lamplight. The brightest thing on the screen. */
    val lamp: Color,
    /** Worn bone — body text. */
    val bone: Color,
    /** Patinated brass. */
    val brass: Color,
    /** The carbolite handle, dark cherry. */
    val carbolite: Color,
    /** SIGNAL: current is flowing. Nothing else may use it. */
    val ember: Color,
    /** The spark, for exactly one frame. */
    val emberHot: Color,
    /** Trouble. Muted, never a fill. */
    val rust: Color,
) {
    public companion object {

        /**
         * The only palette. Zales is one art space with no light/dark switch:
         * a gloomy, realistic night forest with depth.
         */
        public val Night: ZalesColors = ZalesColors(
            void = Color(0xFF060A09),
            deep = Color(0xFF0A0F0E),
            thicket = Color(0xFF101815),
            cold = Color(0xFF2C3A3E),
            breath = Color(0xFF46595C),
            watch = Color(0xFF7A8F8B),
            rime = Color(0xFF849EA2),
            lamp = Color(0xFFF0D9A8),
            bone = Color(0xFFE6DCC6),
            brass = Color(0xFFA8752E),
            carbolite = Color(0xFF3A2018),
            ember = Color(0xFFE0762A),
            emberHot = Color(0xFFFFB061),
            rust = Color(0xFF93331E),
        )
    }
}
