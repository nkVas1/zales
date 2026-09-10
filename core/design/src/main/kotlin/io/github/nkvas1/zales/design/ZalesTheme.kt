// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle

/**
 * Zales does not use a Material theme. The visual system is its own, so the
 * theme carries exactly two things — the palette and the type scale — and
 * nothing that would let a stray Material component leak in and look generic.
 */
@Composable
public fun ZalesTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) ZalesColors.Night else ZalesColors.Fog
    CompositionLocalProvider(
        LocalZalesColors provides colors,
        LocalZalesTypography provides ZalesTypography.Default,
        LocalZalesTextStyle provides ZalesTypography.Default.body,
        content = content,
    )
}

/** Access point for everything in the design system: `Zales.colors`, `Zales.type`. */
public object Zales {
    public val colors: ZalesColors
        @Composable
        @ReadOnlyComposable
        get() = LocalZalesColors.current

    public val type: ZalesTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalZalesTypography.current
}

internal val LocalZalesColors = staticCompositionLocalOf<ZalesColors> {
    error("ZalesColors requested outside ZalesTheme")
}

internal val LocalZalesTypography = staticCompositionLocalOf<ZalesTypography> {
    error("ZalesTypography requested outside ZalesTheme")
}

internal val LocalZalesTextStyle = staticCompositionLocalOf<TextStyle> {
    error("Text style requested outside ZalesTheme")
}
