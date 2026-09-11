// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.design.component

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import io.github.nkvas1.zales.design.LocalZalesTextStyle
import io.github.nkvas1.zales.design.Zales

/**
 * The only text primitive in Zales.
 *
 * Built on [BasicText] rather than a Material `Text` on purpose: the app has
 * no Material theme to inherit colour from, and routing every string through
 * one composable is what makes the "nothing below 16sp" rule enforceable.
 */
@Composable
public fun ZalesText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalZalesTextStyle.current,
    color: Color = Zales.colors.bone,
    align: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.merge(color = color, textAlign = align),
        maxLines = maxLines,
    )
}

/**
 * The height of [lines] lines in [style], as a fixed size.
 *
 * For slots whose contents change but whose height must not: a sentence that
 * grows from one line to two is not a reason for everything above it to move.
 */
@Composable
public fun linesHigh(style: TextStyle, lines: Int): Dp =
    with(LocalDensity.current) { (style.lineHeight * lines).toDp() }
