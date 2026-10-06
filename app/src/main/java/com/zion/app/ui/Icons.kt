package com.zion.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The same path data the Windows XAML uses for every icon. */
object Paths {
    const val Gear = "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"
    const val Back = "M 5 0 L 0 5 L 5 10"
    const val Chevron = "M 0 0 L 4.5 4.5 L 0 9"
    const val ChevronLong = "M 0 0 L 5 5 L 0 10"
    const val Close = "M 0 0 L 8 8 M 8 0 L 0 8"
    const val Plus = "M 5 0 V 10 M 0 5 H 10"
    const val Check = "M 0 4.5 L 3.5 8 L 10 1"
    const val Globe = "M 12 2 A 10 10 0 1 0 12 22 A 10 10 0 1 0 12 2 M 2 12 H 22 M 12 2 C 8 6 8 18 12 22 M 12 2 C 16 6 16 18 12 22"
    const val Down = "M 5 0 V 10 M 0.5 5.5 L 5 10 L 9.5 5.5"
    const val Up = "M 5 10 V 0 M 0.5 4.5 L 5 0 L 9.5 4.5"
    const val Trash = "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"
    const val Link = "M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z"
    const val Speed = "M20.38 8.57l-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0-.27-10.44zm-9.79 6.84a2 2 0 0 0 2.83 0l5.66-8.49-8.49 5.66a2 2 0 0 0 0 2.83z"
    const val Refresh = "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"
    const val Star = "M12 2.5l2.94 5.96 6.58.96-4.76 4.64 1.12 6.55L12 17.52l-5.88 3.09 1.12-6.55L2.48 9.42l6.58-.96z"
    const val Edit = "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"
    const val Web = "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6h-2.95c-.32-1.25-.78-2.45-1.38-3.56 1.84.63 3.37 1.91 4.33 3.56zM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96zM4.26 14C4.1 13.36 4 12.69 4 12s.1-1.36.26-2h3.38c-.08.66-.14 1.32-.14 2 0 .68.06 1.34.14 2H4.26zm.82 2h2.95c.32 1.25.78 2.45 1.38 3.56-1.84-.63-3.37-1.9-4.33-3.56zm2.95-8H5.08c.96-1.66 2.49-2.93 4.33-3.56C8.81 5.55 8.35 6.75 8.03 8zM12 19.96c-.83-1.2-1.48-2.53-1.91-3.96h3.82c-.43 1.43-1.08 2.76-1.91 3.96zM14.34 14H9.66c-.09-.66-.16-1.32-.16-2 0-.68.07-1.35.16-2h4.68c.09.65.16 1.32.16 2 0 .68-.07 1.34-.16 2zm.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95c-.96 1.65-2.49 2.93-4.33 3.56zM16.36 14c.08-.66.14-1.32.14-2 0-.68-.06-1.34-.14-2h3.38c.16.64.26 1.31.26 2s-.1 1.36-.26 2h-3.38z"
    const val PowerArc = "M 13.5 8 A 15 15 0 1 0 28.5 8"
    const val DropArrow = "M 0 0 L 4 4 L 8 0"
}

private class ParsedPath(val path: Path, val bounds: Rect)

private val cache = HashMap<String, ParsedPath>()

private fun parse(d: String): ParsedPath = cache.getOrPut(d) {
    val p = PathParser().parsePathString(d).toPath()
    ParsedPath(p, p.getBounds())
}

/**
 * Draws XAML-style path data. With [stretch] the path is scaled uniformly into width × height (WPF
 * Stretch="Uniform"); without it the path is drawn 1:1 in dp, like a WPF Path with no size set.
 */
@Composable
fun PathIcon(
    data: String,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    height: Dp? = null,
    stretch: Boolean = width != null,
    fill: Color? = null,
    stroke: Color? = null,
    strokeWidth: Dp = 1.6.dp,
) {
    val parsed = remember(data) { parse(data) }
    val density = LocalDensity.current
    val sw = with(density) { strokeWidth.toPx() }
    val w = width ?: with(density) { (parsed.bounds.right + if (stroke != null) sw / density.density else 0f).dp }
    val h = height ?: with(density) { (parsed.bounds.bottom + if (stroke != null) sw / density.density else 0f).dp }
    Canvas(modifier.size(w, h)) {
        val b = parsed.bounds
        if (stretch && b.width > 0 && b.height > 0) {
            val inset = if (stroke != null) sw / 2 else 0f
            val sx = (size.width - inset * 2) / b.width
            val sy = (size.height - inset * 2) / b.height
            val s = minOf(sx, sy)
            val dx = (size.width - b.width * s) / 2 - b.left * s
            val dy = (size.height - b.height * s) / 2 - b.top * s
            withTransform({
                translate(dx, dy)
                scale(s, s, pivot = androidx.compose.ui.geometry.Offset.Zero)
            }) {
                fill?.let { drawPath(parsed.path, it, style = Fill) }
                stroke?.let { drawPath(parsed.path, it, style = Stroke(width = sw / s, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            }
        } else {
            val d = density.density
            val off = if (stroke != null) sw / 2 else 0f
            translate(off, off) {
                withTransform({ scale(d, d, pivot = androidx.compose.ui.geometry.Offset.Zero) }) {
                    fill?.let { drawPath(parsed.path, it, style = Fill) }
                    stroke?.let { drawPath(parsed.path, it, style = Stroke(width = sw / d, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                }
            }
        }
    }
}
