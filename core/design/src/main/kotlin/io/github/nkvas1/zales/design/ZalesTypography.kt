// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Golos Text for everything a person reads, JetBrains Mono for engraved data.
 *
 * Golos was drawn by Paratype and Smena for Russian state services — that is,
 * designed for a reader who does not want to work at it. Its Cyrillic is native,
 * not Latin with extra glyphs.
 *
 * Both are variable fonts: one file per family, every weight cut from it.
 */
private fun golos(weight: FontWeight) = Font(
    resId = R.font.golos_text,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private fun mono(weight: FontWeight) = Font(
    resId = R.font.jetbrains_mono,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

internal val GolosText = FontFamily(
    golos(FontWeight.Normal),
    golos(FontWeight.Medium),
    golos(FontWeight.SemiBold),
    golos(FontWeight.Bold),
    golos(FontWeight.Black),
)

internal val JetBrainsMono = FontFamily(mono(FontWeight.Normal), mono(FontWeight.Medium))

/**
 * The type scale, built grandfather-first: the base size is 18sp, not 14sp,
 * and **nothing in this app is smaller than 16sp** — with a single exception,
 * [nameplate], which carries technical data and never instructions.
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
                fontFamily = GolosText,
                fontWeight = FontWeight.Black,
                fontSize = 56.sp,
                lineHeight = 60.sp,
                letterSpacing = 0.06.em,
            ),
            title = TextStyle(
                fontFamily = GolosText,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                lineHeight = 34.sp,
            ),
            body = TextStyle(
                fontFamily = GolosText,
                fontWeight = FontWeight.Normal,
                fontSize = 18.sp,
                lineHeight = 27.sp,
            ),
            caption = TextStyle(
                fontFamily = GolosText,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
            nameplate = TextStyle(
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                letterSpacing = 0.08.em,
            ),
        )
    }
}
