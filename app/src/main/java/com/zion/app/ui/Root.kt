package com.zion.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zion.app.model.AppScreen
import com.zion.app.ui.screens.DashboardScreen
import com.zion.app.ui.screens.DeleteAllSheet
import com.zion.app.ui.screens.DnsSheet
import com.zion.app.ui.screens.ExcludedAppsScreen
import com.zion.app.ui.screens.ExcludedSitesScreen
import com.zion.app.ui.screens.AppPickerSheet
import com.zion.app.ui.screens.ServerEditScreen
import com.zion.app.ui.screens.ServerListScreen
import com.zion.app.ui.screens.SettingsScreen
import com.zion.app.ui.screens.SubscriptionsScreen
import com.zion.app.vm.MainViewModel

/** The window: backdrop, one screen at a time (fade + rise on entry), bottom sheets and message boxes on top. */
@Composable
fun ZionRoot(vm: MainViewModel) {
    @Suppress("UNUSED_VARIABLE") val tick = vm.tick // redraw when servers change in place
    // Text follows the system size up to 130%: beyond that the fixed-size tiles and pills cannot hold it
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, minOf(density.fontScale, MAX_FONT_SCALE)),
    ) {
    Box(Modifier.fillMaxSize().background(Color(0xFF08090F))) {
        Backdrop()
        // Tablets and wide windows: the app keeps the width of the Windows window, centred, instead of stretching
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().wrapContentWidth().widthIn(max = MAX_CONTENT_WIDTH)) {
            AnimatedContent(
                targetState = vm.currentScreen,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(280, easing = FastOutSlowInEasing)) { 30 }) togetherWith ExitTransition.None
                },
                label = "screen",
            ) { screen ->
                when (screen) {
                    AppScreen.Dashboard -> DashboardScreen(vm)
                    AppScreen.ServerList -> ServerListScreen(vm)
                    AppScreen.ServerEdit -> ServerEditScreen(vm)
                    AppScreen.Settings -> SettingsScreen(vm)
                    AppScreen.Subscriptions -> SubscriptionsScreen(vm)
                    AppScreen.Exclusions -> ExcludedAppsScreen(vm)
                    AppScreen.ExcludedSites -> ExcludedSitesScreen(vm)
                }
            }
        }
        DnsSheet(vm)
        DeleteAllSheet(vm)
        AppPickerSheet(vm)
        MessageBox(vm)
    }
    }
}

/** Width of the app on large screens (the Windows window is 425 wide plus its margins). */
val MAX_CONTENT_WIDTH = 480.dp
private const val MAX_FONT_SCALE = 1.3f

/** Deep gradient with a faint cool tint in the lower-left corner. */
@Composable
private fun Backdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.linearGradient(listOf(Color(0xFF10111B), Color(0xFF08090F)), start = Offset.Zero, end = Offset(size.width * 0.35f, size.height)))
        val r = 230.dp.toPx()
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFF5B8CFF).copy(alpha = 0.24f),
                0.5f to Color(0xFF5B8CFF).copy(alpha = 0.24f * 0.4f),
                1f to Color.Transparent,
                center = Offset(0f, size.height + 20.dp.toPx()), radius = r,
            ),
            radius = r, center = Offset(0f, size.height + 20.dp.toPx()),
        )
    }
}

/** The Windows message box, in Zion's style: title, text, OK. */
@Composable
private fun MessageBox(vm: MainViewModel) {
    val d = vm.dialog
    BottomSheet(visible = d != null, onDismiss = { vm.dialog = null }, padding = androidx.compose.foundation.layout.PaddingValues(18.dp, 10.dp, 18.dp, 16.dp)) {
        if (d != null) {
            Spacer(Modifier.height(16.dp))
            Txt(d.first, Z.text(16.sp, FontWeight.SemiBold))
            Spacer(Modifier.height(6.dp))
            Txt(d.second, Z.text(12.sp, color = Z.TextSecondary))
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                PrimaryBtn("OK", { vm.dialog = null }, Modifier.widthIn(min = 120.dp))
            }
        }
    }
}

