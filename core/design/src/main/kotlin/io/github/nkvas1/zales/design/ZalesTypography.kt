// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The type scale, built grandfather-first: the base size is 18sp, not 14sp,
 * and **nothing in this app is smaller than 16sp** — with a single exception,
 * [nameplate], which carries technical data and never instructions.
 *
 * Golos Text and JetBrains Mono arrive in phase 3 (see docs/ROADMAP.md);
 * until the font files are in the repository this falls back to the system
 * families so that the scale itself can already be built against.
 */
@Immutable
public data class ZalesTypography(
    /** The one big word: ЗАКРЫТО / ОТКРЫТО. Dithered when rendered. */
    val state: TextStyle,
    /** Screen titles. */
    val title: TextStyle,
    /** Body copy. The workhorse. */
    val body: TextStyle,
    /** Secondary line under body copy. Still 16sp. */
    val caption: TextStyle,
    /** Engraved technical plate: version, latency, throughput. Never prose. */
    val nameplate: TextStyle,
) {
    public companion object {
        public val Default: ZalesTypography = ZalesTypography(
            state = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Black,
                fontSize = 64.sp,
                lineHeight = 68.sp,
                letterSpacing = 0.04.em,
            ),
            title = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                lineHeight = 34.sp,
            ),
            body = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 18.sp,
                lineHeight = 27.sp,
            ),
            caption = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
            nameplate = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                letterSpacing = 0.06.em,
            ),
        )
    }
}
