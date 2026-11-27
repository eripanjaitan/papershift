package com.papershift.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.papershift.R

/**
 * Plus Jakarta Sans (SIL OFL 1.1), PRD §8. One bundled variable font (weights 200–800)
 * covers every weight the Material type scale uses, at 172 KB.
 */
@OptIn(ExperimentalTextApi::class)
val PlusJakartaSans = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(
            resId = R.font.plus_jakarta_sans,
            weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
        )
    },
)

private val Base = Typography()

private fun TextStyle.jakarta() = copy(fontFamily = PlusJakartaSans)

/** Material 3 type scale with Plus Jakarta Sans in place of the default font. */
val PaperShiftTypography = Typography(
    displayLarge = Base.displayLarge.jakarta(),
    displayMedium = Base.displayMedium.jakarta(),
    displaySmall = Base.displaySmall.jakarta(),
    headlineLarge = Base.headlineLarge.jakarta(),
    headlineMedium = Base.headlineMedium.jakarta(),
    headlineSmall = Base.headlineSmall.jakarta(),
    titleLarge = Base.titleLarge.jakarta(),
    titleMedium = Base.titleMedium.jakarta(),
    titleSmall = Base.titleSmall.jakarta(),
    bodyLarge = Base.bodyLarge.jakarta(),
    bodyMedium = Base.bodyMedium.jakarta(),
    bodySmall = Base.bodySmall.jakarta(),
    labelLarge = Base.labelLarge.jakarta(),
    labelMedium = Base.labelMedium.jakarta(),
    labelSmall = Base.labelSmall.jakarta(),
)
