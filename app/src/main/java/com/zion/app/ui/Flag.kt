package com.zion.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zion.app.core.Countries
import java.util.concurrent.ConcurrentHashMap

private val flagCache = ConcurrentHashMap<String, ImageBitmap>()
private val missing = ConcurrentHashMap.newKeySet<String>()

/** The country's flag (bundled PNGs), a globe when unknown, a rocket for "auto" servers (FlagBadge). */
@Composable
fun FlagBadge(country: String, width: Dp = 24.dp, height: Dp = 16.dp) {
    val context = LocalContext.current
    val code = remember(country) { Countries.resolveIsoCode(country) }
    val bmp = remember(code) {
        if (code.isEmpty() || code == "un" || code == "auto" || code in missing) null
        else flagCache[code] ?: runCatching {
            context.assets.open("flags/$code.png").use { BitmapFactory.decodeStream(it) }.asImageBitmap()
        }.getOrNull()?.also { flagCache[code] = it } ?: run { missing += code; null }
    }
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.size(width, height).clip(shape).background(Color(0xFF1A1D2B)).border(1.dp, Color(0x33FFFFFF), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Txt(if (code == "auto") "🚀" else "🌐", Z.text(11.sp))
    }
}
