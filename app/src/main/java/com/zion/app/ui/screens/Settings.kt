package com.zion.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zion.app.core.DirectSites
import com.zion.app.model.AppScreen
import com.zion.app.ui.CardButton
import com.zion.app.ui.DangerBtn
import com.zion.app.ui.EmptyState
import com.zion.app.ui.Glass
import com.zion.app.ui.IconBtn
import com.zion.app.ui.Overline
import com.zion.app.ui.PathIcon
import com.zion.app.ui.Paths
import com.zion.app.ui.PrimaryBtn
import com.zion.app.ui.RowAction
import com.zion.app.ui.ScreenHeader
import com.zion.app.ui.SettingRow
import com.zion.app.ui.TonalBtn
import com.zion.app.ui.Txt
import com.zion.app.ui.UndoBar
import com.zion.app.ui.Z
import com.zion.app.ui.ZField
import com.zion.app.vm.MainViewModel
import com.zion.app.vm.SubscriptionRow

@Composable
fun SettingsScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 18.dp)) {
        ScreenHeader("Настройки", { vm.navigate(AppScreen.Dashboard) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Overline("ПОДКЛЮЧЕНИЕ", Modifier.padding(start = 4.dp, top = 2.dp, bottom = 8.dp))
            SettingRow("Автопереключение сервера", "Переход на другой сервер, если текущий недоступен", vm.autoServerFailover, vm::setAutoFailover,
                Modifier.padding(bottom = 8.dp))

            Overline("ИСКЛЮЧЕНИЯ", Modifier.padding(start = 4.dp, top = 10.dp, bottom = 8.dp))
            NavCard("Приложения мимо VPN", vm.excludedApps.summary) { vm.navigate(AppScreen.Exclusions) }
            NavCard("Сайты мимо VPN", vm.excludedSites.summary) { vm.navigate(AppScreen.ExcludedSites) }

            Overline("ЗАЩИТА", Modifier.padding(start = 4.dp, top = 10.dp, bottom = 8.dp))
            SettingRow("Блокировать WebRTC", "Запрет STUN-запросов браузера", vm.blockWebRtc, vm::setWebRtc, Modifier.padding(bottom = 8.dp))
            SettingRow("Блокировать QUIC", "Сайты работают через HTTPS вместо QUIC", vm.blockQuic, vm::setQuic, Modifier.padding(bottom = 8.dp))
            SettingRow("Блокировать трекеры", "Блокировка аналитики и рекламных сетей", vm.blockTrackers, vm::setTrackers, Modifier.padding(bottom = 8.dp))

            Overline("ДИАГНОСТИКА", Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp))
            Glass {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Txt("Журнал событий", Z.text(13.sp, FontWeight.SemiBold))
                        Spacer(Modifier.height(3.dp))
                        Txt("Подключения, ошибки и смены серверов", Z.text(11.sp, color = Z.TextMuted))
                    }
                    Spacer(Modifier.width(14.dp))
                    TonalBtn(vm.copyLogsButtonText, { vm.copyLogs() }, height = 32.dp, size = 11.5f, minWidth = 104.dp)
                }
            }
        }
    }
}

@Composable
private fun NavCard(title: String, summary: String, onClick: () -> Unit) {
    CardButton(onClick, Modifier.fillMaxWidth().padding(bottom = 8.dp), PaddingValues(14.dp, 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(title, Z.text(13.sp, FontWeight.SemiBold))
                Spacer(Modifier.height(3.dp))
                Txt(summary, Z.text(11.sp, color = Z.TextMuted), ellipsis = true)
            }
            Spacer(Modifier.width(12.dp))
            PathIcon(Paths.ChevronLong, stroke = Z.TextMuted)
            Spacer(Modifier.width(4.dp))
        }
    }
}

// ============================================================================== subscriptions

@Composable
fun SubscriptionsScreen(vm: MainViewModel) {
    val s = vm.subscriptions
    Column(Modifier.fillMaxSize().padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 18.dp)) {
        ScreenHeader("Подписки", { vm.navigate(AppScreen.ServerList) }, s.rows.size.toString()) {
            IconBtn(Paths.Refresh, { s.refreshAll() }, iconSize = 14.dp, enabled = s.rows.isNotEmpty())
        }

        // Add a link: with an empty field the button pastes from the clipboard
        Row(Modifier.fillMaxWidth()) {
            ZField(s.newUrl, s::onUrlChanged, "Ссылка на подписку: https://…", Modifier.weight(1f), height = 40.dp, onDone = { s.add() })
            Spacer(Modifier.width(8.dp))
            PrimaryBtn(if (s.isAdding) "Загрузка…" else if (s.hasNewUrl) "Добавить" else "Вставить", { s.add() },
                Modifier.widthIn(min = 104.dp).padding(horizontal = 0.dp), height = 40.dp)
        }
        if (s.status.isNotEmpty()) {
            Txt(s.status, Z.text(11.5.sp, color = if (s.statusIsError) Z.Danger else Z.TextSecondary), Modifier.padding(start = 4.dp, top = 8.dp))
        }
        Spacer(Modifier.height(12.dp))

        Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 10.dp)) {
            LazyColumn {
                items(s.rows, key = { it.entry.id }) { row -> SubscriptionCard(vm, row) }
            }
            if (s.rows.isEmpty()) {
                EmptyState("Подписок пока нет", "Скопируйте ссылку подписки у провайдера и нажмите «Вставить». Подписок может быть сколько угодно.",
                    Modifier.align(Alignment.Center).padding(bottom = 40.dp))
            }
            UndoBar(s.isUndoVisible, s.undoMessage, "Вернуть", { s.undo() }, Modifier.align(Alignment.BottomCenter))
        }

        SettingRow("Автообновление", "Обновление всех подписок раз в сутки", vm.autoUpdateSubscription, vm::setAutoUpdate)
    }
}

@Composable
private fun SubscriptionCard(vm: MainViewModel, row: SubscriptionRow) {
    val s = vm.subscriptions
    Glass(Modifier.fillMaxWidth().padding(bottom = 8.dp), padding = PaddingValues(start = 14.dp, top = 12.dp, end = 10.dp, bottom = 12.dp)) {
        Row {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Txt(row.title, Z.text(13.sp, FontWeight.SemiBold), ellipsis = true)
                Txt(row.host, Z.text(10.5.sp, color = Z.TextMuted), Modifier.padding(top = 2.dp), ellipsis = true)
                Txt(row.statusText, Z.text(11.sp, color = if (row.hasError) Z.Danger else Z.TextSecondary), Modifier.padding(top = 7.dp))
                if (row.hasPlan) {
                    Spacer(Modifier.height(9.dp))
                    if (row.hasQuota) {
                        Box(Modifier.fillMaxWidth().height(4.dp).padding(bottom = 0.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x1FFFFFFF))) {
                            Box(
                                Modifier.fillMaxWidth(row.usedFraction.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp))
                                    .then(if (row.planWarning) Modifier.background(Z.Danger) else Modifier.background(Z.AccentGradient))
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Txt(row.planText, Z.text(11.sp, color = if (row.planWarning) Z.Warning else Z.TextMuted))
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                IconBtn(Paths.Refresh, { s.refresh(row) }, size = 30.dp, iconSize = 12.dp, enabled = !row.isUpdating)
                Spacer(Modifier.width(6.dp))
                // Delete: the first tap arms it (turns into a red "Удалить?"), the second removes
                if (row.isDeleteArmed) DangerBtn("Удалить?", { s.deleteClicked(row) }, Modifier.widthIn(min = 84.dp), height = 30.dp, size = 11.5f)
                else IconBtn(Paths.Trash, { s.deleteClicked(row) }, size = 30.dp, iconSize = 11.dp, iconHeight = 12.dp, danger = true)
            }
        }
    }
}

// ============================================================================== apps outside the VPN

@Composable
fun ExcludedAppsScreen(vm: MainViewModel) {
    val s = vm.excludedApps
    Column(Modifier.fillMaxSize().padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 18.dp)) {
        ScreenHeader("Мимо VPN", { vm.navigate(AppScreen.Settings) }, s.rows.size.toString())
        if (s.status.isNotEmpty()) Txt(s.status, Z.text(11.5.sp, color = Z.AccentHi), Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp))

        Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 10.dp)) {
            LazyColumn {
                items(s.rows, key = { it.packageName }) { app ->
                    Glass(Modifier.fillMaxWidth().padding(bottom = 8.dp), radius = 14.dp, padding = PaddingValues(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Z.GlassHover), contentAlignment = Alignment.Center) {
                                s.icon(app.packageName)?.let { Image(it, null, Modifier.size(22.dp)) }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Txt(app.displayName.ifBlank { app.packageName }, Z.text(13.sp, FontWeight.SemiBold), ellipsis = true)
                                Txt(app.packageName, Z.text(10.5.sp, color = Z.TextMuted), Modifier.padding(top = 2.dp), ellipsis = true)
                            }
                            RowAction({ s.remove(app) }) { PathIcon(Paths.Close, stroke = Z.Danger) }
                        }
                    }
                }
            }
            if (s.rows.isEmpty()) {
                EmptyState("Пока все приложения идут через VPN", "Нажмите «Добавить приложение» и выберите его из списка.",
                    Modifier.align(Alignment.Center).padding(bottom = 30.dp))
            }
        }
        PrimaryBtn("Добавить приложение", { s.openPicker() }, Modifier.fillMaxWidth(), height = 42.dp, plus = true)
    }
}

// ============================================================================== sites outside the VPN

@Composable
fun ExcludedSitesScreen(vm: MainViewModel) {
    val s = vm.excludedSites
    Column(Modifier.fillMaxSize().padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 18.dp)) {
        ScreenHeader("Сайты мимо VPN", { vm.navigate(AppScreen.Settings) }, s.rows.size.toString())

        Row(Modifier.fillMaxWidth()) {
            ZField(s.newSite, s::onSiteChanged, "Адрес сайта, например kinopoisk.ru", Modifier.weight(1f), height = 40.dp, onDone = { s.add() })
            Spacer(Modifier.width(8.dp))
            PrimaryBtn(if (s.hasNewSite) "Добавить" else "Вставить", { s.add() }, Modifier.widthIn(min = 104.dp), height = 40.dp)
        }
        if (s.status.isNotEmpty()) {
            Txt(s.status, Z.text(11.5.sp, color = if (s.statusIsError) Z.Danger else Z.AccentHi), Modifier.padding(start = 4.dp, top = 8.dp))
        }
        Spacer(Modifier.height(12.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn {
                items(s.rows, key = { it }) { domain ->
                    Glass(Modifier.fillMaxWidth().padding(bottom = 8.dp), radius = 14.dp, padding = PaddingValues(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Z.GlassHover), contentAlignment = Alignment.Center) {
                                PathIcon(Paths.Web, width = 16.dp, height = 16.dp, fill = Z.AccentHi)
                            }
                            Spacer(Modifier.width(12.dp))
                            Txt(DirectSites.display(domain), Z.text(13.sp, FontWeight.SemiBold), Modifier.weight(1f), ellipsis = true)
                            RowAction({ s.remove(domain) }) { PathIcon(Paths.Close, stroke = Z.Danger) }
                        }
                    }
                }
            }
            if (s.rows.isEmpty()) {
                EmptyState("Пока все сайты идут через VPN", "Поддомены добавляются автоматически.", Modifier.align(Alignment.Center).padding(bottom = 40.dp))
            }
        }
    }
}

