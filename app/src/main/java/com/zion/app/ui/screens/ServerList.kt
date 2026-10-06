package com.zion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zion.app.model.AppScreen
import com.zion.app.model.ProxyItem
import com.zion.app.model.ProxyStatus
import com.zion.app.ui.EmptyState
import com.zion.app.ui.FlagBadge
import com.zion.app.ui.IconBtn
import com.zion.app.ui.Overline
import com.zion.app.ui.PathIcon
import com.zion.app.ui.Paths
import com.zion.app.ui.Pill
import com.zion.app.ui.PrimaryBtn
import com.zion.app.ui.RowAction
import com.zion.app.ui.ScreenHeader
import com.zion.app.ui.Txt
import com.zion.app.ui.UndoBar
import com.zion.app.ui.Z
import com.zion.app.ui.rememberSource
import com.zion.app.ui.tap
import com.zion.app.vm.MainViewModel
import com.zion.app.vm.ServerRow

@Composable
fun ServerListScreen(vm: MainViewModel) {
    @Suppress("UNUSED_VARIABLE") val tick = vm.tick
    val list = vm.serverList
    Column(Modifier.fillMaxSize().padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 18.dp)) {
        // Server count; while the servers are being checked: progress ("12/47")
        ScreenHeader("Серверы", { vm.navigate(AppScreen.Dashboard) }, if (vm.isCheckingServers) vm.checkProgressText else list.serverCount.toString()) {
            IconBtn(Paths.Trash, { list.askDeleteAll() }, iconSize = 13.dp, iconHeight = 14.dp, danger = true, enabled = vm.deletableProxyCount > 0)
            IconBtn(Paths.Link, { vm.navigate(AppScreen.Subscriptions) })
            IconBtn(Paths.Speed, { list.checkServers() }, enabled = !vm.isCheckingServers && list.serverCount > 0)
            IconBtn(Paths.Refresh, { list.refreshSubscriptions() }, iconSize = 14.dp)
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 10.dp)) {
            val rows = list.rows()
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(rows, key = { _, r -> r.key }) { index, row ->
                    when (row) {
                        is ServerRow.Header -> Row(
                            Modifier.fillMaxWidth().padding(start = 4.dp, top = if (index == 0) 2.dp else 10.dp, end = 6.dp, bottom = 8.dp)
                        ) {
                            Overline(row.caption, Modifier.weight(1f))
                            Overline(row.count.toString())
                        }
                        is ServerRow.SubHeader -> Row(
                            Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, end = 6.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Txt(row.title, Z.text(11.5.sp, FontWeight.SemiBold, Z.TextSecondary), Modifier.padding(end = 10.dp), ellipsis = true)
                            Box(Modifier.weight(1f).height(1.dp).background(Z.Stroke))
                        }
                        is ServerRow.Server -> ServerCard(vm, row.proxy)
                    }
                }
            }

            if (vm.proxies.isEmpty()) {
                EmptyState("Список пуст", "Добавьте сервер или ссылку на подписку кнопкой ниже.", Modifier.align(Alignment.Center).padding(bottom = 40.dp))
            }

            UndoBar(list.isUndoVisible, list.undoMessage, "Отменить", { list.undo() }, Modifier.align(Alignment.BottomCenter))
        }

        PrimaryBtn("Добавить сервер", { vm.openEditor(null) }, Modifier.fillMaxWidth(), height = 42.dp, plus = true)
    }
}

@Composable
private fun ServerCard(vm: MainViewModel, p: ProxyItem) {
    // Servers are changed in place (check results, stars): read the change counter so the card redraws
    @Suppress("UNUSED_VARIABLE") val tick = vm.tick
    val selected = vm.isSelected(p)
    val src = rememberSource()
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(16.dp)
    val bg = when { selected -> Z.SelectedBg; pressed -> Z.GlassHover; else -> Z.Glass }
    val border = when { selected -> Z.Accent; pressed -> Z.StrokeHover; else -> Z.Stroke }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(shape).background(bg).border(1.dp, border, shape)
            .tap(src) {
                vm.selectProxy(p)
                vm.navigate(AppScreen.Dashboard)
            }
            .padding(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) Color(0x338B7DFF) else Z.GlassHover), contentAlignment = Alignment.Center) {
            FlagBadge(p.country)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            Txt(p.cleanName, Z.text(13.sp, FontWeight.SemiBold), ellipsis = true)
            // Address; "Не отвечает" when the last check found the server dead
            val offline = p.status == ProxyStatus.Offline
            Txt(if (offline) "Не отвечает" else p.displayAddress, Z.text(10.5.sp, color = if (offline) Z.Danger else Z.TextMuted),
                Modifier.padding(top = 2.dp), ellipsis = true)
        }
        Pill(p.protocolBadge, 9f, Modifier.padding(end = 6.dp))
        // Favourite: outline star, filled amber when marked
        RowAction({ vm.serverList.toggleFavorite(p) }) {
            PathIcon(Paths.Star, width = 13.dp, height = 13.dp, fill = if (p.isFavorite) Z.Warning else null,
                stroke = if (p.isFavorite) Z.Warning else Z.TextMuted, strokeWidth = 1.6.dp)
        }
        RowAction({ vm.openEditor(p) }) { PathIcon(Paths.Edit, width = 11.dp, height = 11.dp, fill = Z.TextSecondary) }
        RowAction({ vm.serverList.deleteProxy(p) }) { PathIcon(Paths.Close, stroke = Z.Danger) }
    }
}
