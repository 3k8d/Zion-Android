package com.zion.app.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** Zion's palette and text styles (Views/Theme.xaml). */
object Z {
    val TextPrimary = Color(0xFFF4F6FB)
    val TextSecondary = Color(0xFFA3ACC2)
    val TextMuted = Color(0xFF6B7690)
    val Glass = Color(0x0CFFFFFF)
    val GlassHover = Color(0x17FFFFFF)
    val Stroke = Color(0x16FFFFFF)
    val StrokeHover = Color(0x33FFFFFF)
    val Accent = Color(0xFF8B7DFF)
    val AccentHi = Color(0xFFB3A9FF)
    val AccentSoft = Color(0x268B7DFF)
    val AccentStroke = Color(0x668B7DFF)
    val Success = Color(0xFF34E0A1)
    val Danger = Color(0xFFFB7185)
    val Warning = Color(0xFFFBBF24)
    val Surface = Color(0xFF151824)
    val SheetBorder = Color(0xFF2A2F42)
    val Backdrop = Color(0xB8050609)
    val SelectedBg = Color(0x1F8B7DFF)
    val FocusBg = Color(0x148B7DFF)
    val DangerSolid = Color(0xFFE5566B)

    val AccentGradient = Brush.linearGradient(listOf(Color(0xFF9D8FFF), Color(0xFF6A5AF9)))

    fun text(size: TextUnit, weight: FontWeight = FontWeight.Normal, color: Color = TextPrimary) =
        TextStyle(fontSize = size, fontWeight = weight, color = color, lineHeight = size * 1.3f)

    val Overline = text(10.sp, FontWeight.SemiBold, TextMuted).copy(letterSpacing = 0.2.sp)
    val FieldLabel = text(11.sp, FontWeight.SemiBold, TextSecondary)
    val ScreenTitle = text(15.5.sp, FontWeight.SemiBold, TextPrimary)
}
