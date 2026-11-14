package com.moboserver.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.moboserver.app.R

// Copiar compose/res/font para android/app/src/main/res/font.
val MoboFont = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val MoboTypography = Typography(
    bodyLarge = TextStyle(fontFamily = MoboFont, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = MoboFont, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = MoboFont, fontSize = 12.sp, lineHeight = 18.sp),
    titleLarge = TextStyle(fontFamily = MoboFont, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = MoboFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    titleSmall = TextStyle(fontFamily = MoboFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    headlineLarge = TextStyle(fontFamily = MoboFont, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp),
    labelLarge = TextStyle(fontFamily = MoboFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = MoboFont, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = MoboFont, fontWeight = FontWeight.Medium, fontSize = 10.sp),
)
