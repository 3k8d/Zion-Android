package com.zion.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zion.app.model.AppScreen
import com.zion.app.ui.CardButton
import com.zion.app.ui.FlagBadge
import com.zion.app.ui.Overline
import com.zion.app.ui.PathIcon
import com.zion.app.ui.Paths
import com.zion.app.ui.Pill
import com.zion.app.ui.QuickToggle
import com.zion.app.ui.Txt
import com.zion.app.ui.Z
import com.zion.app.ui.rememberSource
import com.zion.app.ui.tap
import com.zion.app.vm.MainViewModel

private val Spline = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

@Composable
fun DashboardScreen(vm: MainViewModel) {
    @Suppress("UNUSED_VARIABLE") val tick = vm.tick
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 18.dp)) {
        // On a short screen (split screen, a tablet held sideways) the dashboard scrolls instead of being cut off
        val compact = maxHeight < 600.dp
        // Phones are taller than the Windows window: the power button and timer grow with the screen
        // (up to +25%) instead of floating in empty space, and the controls get finger-sized
        val heroScale = ((maxHeight - 640.dp) / 280.dp * 0.25f + 1f).coerceIn(1f, 1.25f)
        val roomy = maxHeight >= 700.dp
        Column(Modifier.fillMaxSize().then(if (compact) Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()) else Modifier)) {
            TopBar(vm, roomy)
            if (compact) Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { Hero(vm, 1f) }
            else {
                // A little above the middle of the free space: the eye reads that as balanced, the exact middle looks low
                Spacer(Modifier.weight(0.42f))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Hero(vm, heroScale) }
                Spacer(Modifier.weight(0.58f))
            }
            BottomCards(vm, roomy)
        }

        // Notification (server switched, Kill Switch problems): takes the place of the session timer; tap to dismiss
        AnimatedVisibility(vm.isToastVisible, Modifier.padding(top = 50.dp), enter = fadeIn(), exit = fadeOut()) {
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 58.dp).clip(shape).background(Z.Surface).border(1.dp, Z.AccentStroke, shape)
                    .tap(rememberSource()) { vm.dismissToast() }.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Z.AccentHi))
                Spacer(Modifier.width(10.dp))
                Txt(vm.toastText, Z.text(12.sp), Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                PathIcon(Paths.Close, stroke = Z.TextMuted)
            }
        }
    }
}

@Composable
private fun TopBar(vm: MainViewModel, roomy: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Wordmark glows in the colour of the power button: a blurred copy sits under the crisp text
        Box(Modifier.padding(start = 2.dp)) {
            // The blur gets 16dp of room on every side, so the glow fades out instead of ending in a hard box
            Box(Modifier.glowRoom(16.dp).alpha(0.9f).blur(14.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded).padding(16.dp)) {
                Txt("Zion", Z.text(19.sp, FontWeight.Bold, Color(vm.statusDotColor)))
            }
            Txt("Zion", Z.text(19.sp, FontWeight.Bold, Color(vm.powerBtnRingStroke)))
        }
        Spacer(Modifier.weight(1f))
        val src = rememberSource()
        val pressed by src.collectIsPressedAsState()
        Box(
            Modifier.size(if (roomy) 44.dp else 36.dp).clip(RoundedCornerShape(12.dp)).background(if (pressed) Z.GlassHover else Color.Transparent)
                .tap(src) { vm.navigate(AppScreen.Settings) },
            contentAlignment = Alignment.Center,
        ) { PathIcon(Paths.Gear, width = 17.dp, height = 17.dp, fill = if (pressed) Z.TextPrimary else Z.TextMuted) }
    }
}

@Composable
private fun Hero(vm: MainViewModel, scale: Float) {
    // The prompt also depends on plain fields (Kill Switch, link health): redraw on every change
    @Suppress("UNUSED_VARIABLE") val tick = vm.tick
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)) {
        // Steps aside while a notification is shown in its place (keeps its space, so nothing jumps)
        Column(Modifier.alpha(if (vm.isToastVisible) 0f else 1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(vm.sessionTimerText, Z.text((44 * scale).sp, FontWeight.Light).copy(fontFeatureSettings = "tnum"))
            Row(Modifier.padding(bottom = 8.dp)) {
                Txt("Трафик за сессию  ·  ", Z.text((11 + (scale - 1) * 4).sp, color = Z.TextMuted))
                Txt(vm.totalTrafficText, Z.text((11 + (scale - 1) * 4).sp, FontWeight.SemiBold, Z.TextSecondary))
            }
        }

        // The ring breathes while the VPN is connected (Compose stops animating when the app is not visible)
        val breathing = rememberInfiniteTransition(label = "breathing")
        val phase by if (vm.isConnected) breathing.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = Spline), RepeatMode.Reverse), label = "p")
                     else androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        val haloScale = 1f + 0.07f * phase
        val glow = 0.35f + (0.9f - 0.35f) * phase
        val btnScale = 1f + 0.02f * phase

        Box(Modifier.size(172.dp * scale), contentAlignment = Alignment.Center) {
            Box(Modifier.size(160.dp * scale).scale(haloScale), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().alpha(0.55f).clip(CircleShape).background(Color(vm.outerHaloBg)))
                Box(Modifier.fillMaxSize().alpha(glow).border(1.5.dp, Color(vm.outerHaloBorder), CircleShape))
            }
            Box(Modifier.size(142.dp * scale).border(1.dp, Color(0x1FFFFFFF), CircleShape))

            val src = rememberSource()
            val pressed by src.collectIsPressedAsState()
            Box(
                Modifier.size(124.dp * scale).scale(btnScale * if (pressed) 0.955f else 1f).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(vm.powerBtnTop), Color(vm.powerBtnBottom)), center = Offset.Unspecified))
                    .border(1.5.dp, Color(vm.powerBtnRingStroke), CircleShape)
                    .tap(src, !vm.isConnecting) { vm.toggleConnection() },
                contentAlignment = Alignment.Center,
            ) { PowerGlyph(42.dp * scale) }
        }

        Spacer(Modifier.height(6.dp * scale))
        Txt(vm.powerPromptText, Z.text((12 + (scale - 1) * 4).sp, FontWeight.SemiBold, Color(vm.powerStatusTextColor)))
    }
}

@Composable
private fun PowerGlyph(glyphSize: Dp) {
    Canvas(Modifier.size(glyphSize)) {
        val u = size.width / 42f
        val stroke = Stroke(width = 3.4f * u, cap = StrokeCap.Round)
        // Arc "M 13.5 8 A 15 15 0 1 0 28.5 8": a circle of radius 15 around (21, 21) open at the top
        drawArc(Color.White, startAngle = -60f, sweepAngle = 300f, useCenter = false,
            topLeft = Offset(6f * u, 6f * u), size = androidx.compose.ui.geometry.Size(30f * u, 30f * u), style = stroke)
        drawLine(Color.White, Offset(21f * u, 2f * u), Offset(21f * u, 15f * u), strokeWidth = 3.4f * u, cap = StrokeCap.Round)
    }
}

@Composable
private fun BottomCards(vm: MainViewModel, roomy: Boolean) {
    // Finger-sized on phones: taller cards and switches (Android recommends 48dp touch targets)
    val cardPadding = PaddingValues(horizontal = 12.dp, vertical = if (roomy) 13.dp else 10.dp)
    val tile = if (roomy) Modifier.heightIn(min = 48.dp) else Modifier
    val gap = if (roomy) 10.dp else 8.dp
    // The selected server and the DNS in use are plain fields: the change counter makes these cards redraw
    @Suppress("UNUSED_VARIABLE") val tick = vm.tick
    Column {
        // Server
        CardButton({ vm.navigate(AppScreen.ServerList) }, Modifier.fillMaxWidth().padding(bottom = gap), cardPadding) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Z.GlassHover), contentAlignment = Alignment.Center) {
                    FlagBadge(vm.serverCardCountry)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Overline("СЕРВЕР")
                    Txt(vm.serverCardSubtitle, Z.text(13.5.sp, FontWeight.SemiBold), Modifier.padding(top = 1.dp), ellipsis = true)
                }
                Pill(vm.serverCardBadge)
                Spacer(Modifier.width(10.dp))
                PathIcon(Paths.Chevron, stroke = Z.TextMuted)
                Spacer(Modifier.width(2.dp))
            }
        }

        // DNS
        CardButton({ vm.isDnsModalOpen = true }, Modifier.fillMaxWidth().padding(bottom = gap), cardPadding) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Z.GlassHover), contentAlignment = Alignment.Center) {
                    PathIcon(Paths.Globe, width = 18.dp, height = 18.dp, stroke = Z.AccentHi, strokeWidth = 1.5.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Overline("DNS")
                    Txt(vm.dnsSubtitleText, Z.text(12.5.sp, FontWeight.SemiBold, Color(vm.dnsSubtitleColor)), Modifier.padding(top = 1.dp), ellipsis = true)
                }
                Pill(vm.dnsBadgeText)
                Spacer(Modifier.width(10.dp))
                PathIcon(Paths.Chevron, stroke = Z.TextMuted)
                Spacer(Modifier.width(2.dp))
            }
        }

        // Live speed
        val shape = RoundedCornerShape(16.dp)
        Row(
            Modifier.fillMaxWidth().padding(bottom = gap).clip(shape).background(Z.Glass).border(1.dp, Z.Stroke, shape)
                .padding(horizontal = 14.dp, vertical = if (roomy) 11.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpeedCell("ЗАГРУЗКА", vm.downSpeedText, Paths.Down, Z.Success, Color(0x1F34E0A1), Modifier.weight(1f))
            Box(Modifier.padding(horizontal = 10.dp, vertical = 2.dp).width(1.dp).height(30.dp).background(Z.Stroke))
            SpeedCell("ОТДАЧА", vm.upSpeedText, Paths.Up, Z.AccentHi, Z.AccentSoft, tile.weight(1f).padding(start = gap / 2))
        }

        // Quick settings
        Row(Modifier.fillMaxWidth().padding(bottom = gap)) {
            QuickToggle("Автоподключение", vm.autoConnectOnStartup, vm::setAutoConnect, tile.weight(1f).padding(end = gap / 2))
            QuickToggle("Обход РФ", vm.bypassDomesticRu, vm::setBypassRu, tile.weight(1f).padding(start = gap / 2))
        }
        Row(Modifier.fillMaxWidth()) {
            QuickToggle("Обход торрентов", vm.bypassTorrents, vm::changeBypassTorrents, tile.weight(1f).padding(end = gap / 2))
            QuickToggle("Kill Switch", vm.killSwitch, vm::changeKillSwitch, tile.weight(1f).padding(start = gap / 2))
        }
    }
}

@Composable
private fun SpeedCell(label: String, value: String, icon: String, tint: Color, bg: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(9.dp)).background(bg), contentAlignment = Alignment.Center) {
            PathIcon(icon, stroke = tint, strokeWidth = 1.7.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Overline(label, size = 9f)
            Txt(value, Z.text(12.5.sp, FontWeight.SemiBold))
        }
    }
}

/** Draws [room] beyond the element's own bounds on every side without taking that space in the layout (for a glow). */
private fun Modifier.glowRoom(room: Dp) = this.then(
    Modifier.layout { measurable, constraints ->
        val pad = room.roundToPx()
        // An unbounded side (inside a scrolling column) must stay unbounded: adding to Infinity overflows
        fun grow(max: Int) = if (max == Constraints.Infinity) max else max + pad * 2
        val placeable = measurable.measure(constraints.copy(maxWidth = grow(constraints.maxWidth), maxHeight = grow(constraints.maxHeight)))
        layout(placeable.width - pad * 2, placeable.height - pad * 2) { placeable.place(-pad, -pad) }
    }
)