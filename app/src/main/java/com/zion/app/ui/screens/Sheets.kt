package com.zion.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zion.app.ui.BottomSheet
import com.zion.app.ui.DangerBtn
import com.zion.app.ui.GhostBtn
import com.zion.app.ui.PathIcon
import com.zion.app.ui.Paths
import com.zion.app.ui.Txt
import com.zion.app.ui.Z
import com.zion.app.ui.ZField
import com.zion.app.ui.rememberSource
import com.zion.app.ui.tap
import com.zion.app.vm.MainViewModel

@Composable
private fun SheetTitle(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, Z.text(15.sp, FontWeight.SemiBold), Modifier.weight(1f))
        val src = rememberSource()
        val pressed by src.collectIsPressedAsState()
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(if (pressed) Z.GlassHover else Color.Transparent).tap(src, true, onClose),
            contentAlignment = Alignment.Center,
        ) { PathIcon(Paths.Close, stroke = if (pressed) Z.TextPrimary else Z.TextMuted) }
    }
}

/** DNS server choice. */
@Composable
fun DnsSheet(vm: MainViewModel) {
    BottomSheet(vm.isDnsModalOpen, { vm.isDnsModalOpen = false }) {
        SheetTitle("DNS-сервер") { vm.isDnsModalOpen = false }
        for ((provider, name) in vm.dnsOptions) {
            val selected = vm.selectedDns == provider
            val src = rememberSource()
            val pressed by src.collectIsPressedAsState()
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().padding(bottom = 5.dp).clip(shape)
                    .background(when { selected -> Z.SelectedBg; pressed -> Z.GlassHover; else -> Z.Glass })
                    .border(1.dp, if (selected) Z.AccentStroke else Color.Transparent, shape)
                    .tap(src) { vm.selectDns(provider) }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(name, Z.text(13.sp, FontWeight.SemiBold, if (selected) Z.AccentHi else Z.TextPrimary), Modifier.weight(1f))
                if (selected) PathIcon(Paths.Check, stroke = Z.AccentHi, strokeWidth = 1.8.dp)
            }
        }
    }
}

/** Confirmation before clearing the server list. */
@Composable
fun DeleteAllSheet(vm: MainViewModel) {
    val list = vm.serverList
    BottomSheet(list.isDeleteAllConfirmOpen, { list.isDeleteAllConfirmOpen = false }, PaddingValues(18.dp, 10.dp, 18.dp, 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x1FFB7185)), contentAlignment = Alignment.Center) {
            PathIcon(Paths.Trash, width = 16.dp, height = 18.dp, fill = Z.Danger)
        }
        Spacer(Modifier.height(14.dp))
        Txt("Очистить список серверов?", Z.text(16.sp, FontWeight.SemiBold))
        Spacer(Modifier.height(6.dp))
        Txt(list.deleteAllConfirmText, Z.text(12.sp, color = Z.TextSecondary))
        Spacer(Modifier.height(4.dp))
        Txt("Передумаете — нажмите «Отменить» в течение 10 секунд после удаления.", Z.text(11.sp, color = Z.TextMuted))
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth()) {
            GhostBtn("Отмена", { list.isDeleteAllConfirmOpen = false }, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            DangerBtn("Очистить", { list.confirmDeleteAll() }, Modifier.weight(1f))
        }
    }
}

/** Installed apps to exclude from the VPN (Windows: running programs). */
@Composable
fun AppPickerSheet(vm: MainViewModel) {
    val s = vm.excludedApps
    BottomSheet(s.isPickerOpen, { s.isPickerOpen = false }, maxHeight = 560.dp) {
        SheetTitle("Приложения") { s.isPickerOpen = false }
        ZField(s.search, { s.search = it }, "Поиск по названию", Modifier.fillMaxWidth().padding(bottom = 10.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp)) {
            val items = s.pickerItems
            LazyColumn {
                items(items, key = { it.packageName }) { app ->
                    val added = s.isAdded(app.packageName)
                    val src = rememberSource()
                    val pressed by src.collectIsPressedAsState()
                    val shape = RoundedCornerShape(16.dp)
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 5.dp).alpha(if (added) 0.45f else 1f).clip(shape)
                            .background(if (pressed && !added) Z.GlassHover else Z.Glass).border(1.dp, if (pressed && !added) Z.StrokeHover else Z.Stroke, shape)
                            .tap(src, !added) { s.pick(app) }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.padding(start = 2.dp, end = 12.dp).size(22.dp)) {
                            s.icon(app.packageName)?.let { Image(it, null, Modifier.size(22.dp)) }
                        }
                        Column(Modifier.weight(1f)) {
                            Txt(app.label, Z.text(12.5.sp, FontWeight.SemiBold), ellipsis = true)
                            Txt(app.packageName, Z.text(10.5.sp, color = Z.TextMuted), ellipsis = true)
                        }
                        if (added) Txt("уже в списке", Z.text(10.5.sp, color = Z.TextMuted), Modifier.padding(start = 8.dp, end = 4.dp))
                    }
                }
            }
            if (s.isLoadingPicker) Txt("Ищу приложения…", Z.text(12.sp, color = Z.TextMuted), Modifier.align(Alignment.Center))
            else if (items.isEmpty()) Txt("Ничего не нашлось.", Z.text(12.sp, color = Z.TextMuted), Modifier.align(Alignment.Center))
        }
    }
}
