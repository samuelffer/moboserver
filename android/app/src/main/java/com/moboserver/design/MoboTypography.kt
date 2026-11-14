package com.moboserver.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.moboserver.app.R

/**
 * Família Inter (SIL OFL 1.1) usada em toda a interface — telas, diálogos e
 * subpáginas. Os arquivos vêm de `arquivos/compose/res/font/` e a licença
 * acompanha o APK em `assets/licenses/INTER-OFL.txt`.
 */
val MoboFont = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Escala da prévia (Design v2): títulos 34/41, corpo 16/24, apoio 13–14. */
val MoboTypography = Typography(
    bodyLarge = TextStyle(fontFamily = MoboFont, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = MoboFont, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = MoboFont, fontSize = 12.sp, lineHeight = 18.sp),
    titleLarge = TextStyle(fontFamily = MoboFont, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = MoboFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    titleSmall = TextStyle(fontFamily = MoboFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    headlineLarge = TextStyle(
        fontFamily = MoboFont,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = (-1.25).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = MoboFont,
        fontWeight = FontWeight.Bold,
        fontSize = 27.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.8).sp,
    ),
    labelLarge = TextStyle(fontFamily = MoboFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = MoboFont, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = MoboFont, fontWeight = FontWeight.Medium, fontSize = 10.sp),
)
