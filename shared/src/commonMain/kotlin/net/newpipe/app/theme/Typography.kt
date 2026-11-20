/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.newpipe.app.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import newpipe.shared.generated.resources.Res
import newpipe.shared.generated.resources.source_sans_3_regular
import newpipe.shared.generated.resources.source_sans_3_semibold
import org.jetbrains.compose.resources.Font

/** Bundled fonts: no installed Windows font or network request is required. */
@Composable
fun boitierTypography(): Typography {
    val family = FontFamily(
        Font(Res.font.source_sans_3_regular, FontWeight.Normal),
        Font(Res.font.source_sans_3_semibold, FontWeight.Medium),
        Font(Res.font.source_sans_3_semibold, FontWeight.SemiBold),
        Font(Res.font.source_sans_3_semibold, FontWeight.Bold)
    )
    fun TextStyle.branded() = copy(
        fontFamily = family,
        fontSize = fontSize * 1.025f,
        lineHeight = lineHeight * 1.025f
    )
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.branded(),
        displayMedium = base.displayMedium.branded(),
        displaySmall = base.displaySmall.branded(),
        headlineLarge = base.headlineLarge.branded(),
        headlineMedium = base.headlineMedium.branded(),
        headlineSmall = base.headlineSmall.branded(),
        titleLarge = base.titleLarge.branded(),
        titleMedium = base.titleMedium.branded(),
        titleSmall = base.titleSmall.branded(),
        bodyLarge = base.bodyLarge.branded(),
        bodyMedium = base.bodyMedium.branded(),
        bodySmall = base.bodySmall.branded(),
        labelLarge = base.labelLarge.branded(),
        labelMedium = base.labelMedium.branded(),
        labelSmall = base.labelSmall.branded()
    )
}
