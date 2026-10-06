package com.zion.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zion.app.core.GeoIp
import com.zion.app.core.ProxyParser
import com.zion.app.model.AppScreen
import com.zion.app.model.ProxyItem
import com.zion.app.model.ProxyProtocol
import com.zion.app.model.ProxyStatus
import com.zion.app.ui.FieldLabel
import com.zion.app.ui.GhostBtn
import com.zion.app.ui.Glass
import com.zion.app.ui.Overline
import com.zion.app.ui.PrimaryBtn
import com.zion.app.ui.ScreenHeader
import com.zion.app.ui.TonalBtn
import com.zion.app.ui.Txt
import com.zion.app.ui.Z
import com.zion.app.ui.ZDropdown
import com.zion.app.ui.ZField
import com.zion.app.vm.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PROTOCOLS = listOf("VLESS" to ProxyProtocol.Vless, "Trojan" to ProxyProtocol.Trojan, "Shadowsocks" to ProxyProtocol.Shadowsocks,
    "VMess" to ProxyProtocol.Vmess, "Hysteria2" to ProxyProtocol.Hysteria2, "TUIC" to ProxyProtocol.Tuic, "SOCKS5" to ProxyProtocol.Socks5,
    "HTTP" to ProxyProtocol.Http)
private val TRANSPORTS = listOf("TCP" to "tcp", "gRPC" to "grpc", "WebSocket (WS)" to "ws", "HTTPUpgrade" to "httpupgrade", "H2" to "h2")
private val SECURITY = listOf("Reality" to "reality", "TLS" to "tls", "None" to "none")
private val FINGERPRINTS = listOf("Chrome" to "chrome", "Firefox" to "firefox", "Safari" to "safari", "Edge" to "edge", "iOS" to "ios", "Random" to "random")
private val CONGESTION = listOf("BBR" to "bbr", "Cubic" to "cubic", "New_Reno" to "new_reno")
private val SS_METHODS = listOf("aes-256-gcm", "aes-128-gcm", "chacha20-ietf-poly1305", "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305")

/** The form's fields (one object, so filling and reading it stay in one place). */
private class Form {
    var name by mutableStateOf(""); var host by mutableStateOf(""); var port by mutableStateOf("")
    var protocol by mutableIntStateOf(0); var uuid by mutableStateOf(""); var transport by mutableIntStateOf(0)
    var security by mutableIntStateOf(0); var fingerprint by mutableIntStateOf(0); var sni by mutableStateOf("")
    var publicKey by mutableStateOf(""); var shortId by mutableStateOf(""); var servicePath by mutableStateOf("")
    var user by mutableStateOf(""); var pass by mutableStateOf(""); var ssMethod by mutableIntStateOf(0)
    var tuicUuid by mutableStateOf(""); var quicPassword by mutableStateOf(""); var quicSni by mutableStateOf("")
    var obfs by mutableStateOf(""); var hopPorts by mutableStateOf(""); var congestion by mutableIntStateOf(0)
    var quick by mutableStateOf("")
    /** The parsed or saved server: keeps what the form does not show (flow, ALPN, …). */
    var source: ProxyItem? = null

    val proto: ProxyProtocol get() = PROTOCOLS[protocol].second

    fun clear() {
        name = ""; host = ""; port = ""; protocol = 0; uuid = ""; transport = 0; security = 0; fingerprint = 0; sni = ""; publicKey = ""
        shortId = ""; servicePath = ""; user = ""; pass = ""; ssMethod = 0; tuicUuid = ""; quicPassword = ""; quicSni = ""; obfs = ""
        hopPorts = ""; congestion = 0; quick = ""; source = null
    }

    private fun <T> idx(list: List<Pair<String, T>>, value: T?, fallback: Int = 0) = list.indexOfFirst { it.second == value }.takeIf { it >= 0 } ?: fallback

    fun fill(p: ProxyItem) {
        clear()
        source = p
        name = p.name; host = p.cleanHost; port = if (p.port > 0) p.port.toString() else "443"
        protocol = idx(PROTOCOLS, p.protocol)
        val fp = p.fingerprint.ifEmpty { "chrome" }.lowercase()
        when (p.protocol) {
            ProxyProtocol.Vless, ProxyProtocol.Vmess -> {
                uuid = p.uuid
                transport = idx(TRANSPORTS, p.transportType.lowercase())
                security = if (p.protocol == ProxyProtocol.Vmess) (if (p.security == "tls") 1 else 2) else idx(SECURITY, p.security.lowercase())
                fingerprint = idx(FINGERPRINTS, fp)
                sni = p.sni; publicKey = p.publicKey; shortId = p.shortId
                servicePath = if (p.transportType == "grpc") p.grpcServiceName else p.wsPath
            }
            ProxyProtocol.Trojan -> {
                uuid = p.password.ifEmpty { p.uuid }
                transport = idx(TRANSPORTS, p.transportType.lowercase())
                security = 1
                fingerprint = idx(FINGERPRINTS, fp)
                sni = p.sni
                servicePath = if (p.transportType == "grpc") p.grpcServiceName else p.wsPath
                pass = p.password
            }
            ProxyProtocol.Shadowsocks -> {
                pass = p.password
                ssMethod = SS_METHODS.indexOf(p.security.lowercase()).takeIf { it >= 0 } ?: 0
            }
            ProxyProtocol.Hysteria2, ProxyProtocol.Tuic -> {
                tuicUuid = p.uuid; quicPassword = p.password; quicSni = p.sni; obfs = p.obfsPassword; hopPorts = p.serverPorts
                congestion = idx(CONGESTION, p.congestionControl.ifEmpty { "bbr" })
            }
            else -> { user = p.username; pass = p.password }
        }
    }

    fun build(): ProxyItem? {
        val h = host.trim()
        if (h.isBlank()) return null
        val prt = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
        val p = ProxyItem(name = name.trim().ifBlank { h }, host = h, port = prt, protocol = proto)
        when (proto) {
            ProxyProtocol.Vless -> {
                p.uuid = uuid.trim(); p.transportType = TRANSPORTS[transport].second; p.security = SECURITY[security].second
                p.fingerprint = FINGERPRINTS[fingerprint].second; p.sni = sni.trim(); p.publicKey = publicKey.trim(); p.shortId = shortId.trim()
                if (p.transportType == "grpc") p.grpcServiceName = servicePath.trim() else p.wsPath = servicePath.trim()
            }
            ProxyProtocol.Trojan -> {
                val pw = pass.trim().ifEmpty { uuid.trim() }
                p.password = pw; p.uuid = pw; p.security = "tls"; p.transportType = TRANSPORTS[transport].second
                p.fingerprint = FINGERPRINTS[fingerprint].second; p.sni = sni.trim()
                if (p.transportType == "grpc") p.grpcServiceName = servicePath.trim() else p.wsPath = servicePath.trim()
            }
            ProxyProtocol.Shadowsocks -> { p.password = pass.trim().ifEmpty { uuid.trim() }; p.security = SS_METHODS[ssMethod] }
            ProxyProtocol.Vmess -> {
                p.uuid = uuid.trim(); p.transportType = TRANSPORTS[transport].second
                p.security = if (security == 1) "tls" else (source?.security?.takeIf { it != "tls" && it.isNotEmpty() } ?: "auto")
                p.fingerprint = FINGERPRINTS[fingerprint].second; p.sni = sni.trim()
                if (p.transportType == "grpc") p.grpcServiceName = servicePath.trim() else p.wsPath = servicePath.trim()
            }
            ProxyProtocol.Hysteria2, ProxyProtocol.Tuic -> {
                p.security = "tls"; p.transportType = ""; p.fingerprint = ""; p.password = quicPassword.trim(); p.sni = quicSni.trim()
                if (proto == ProxyProtocol.Tuic) { p.uuid = tuicUuid.trim(); p.congestionControl = CONGESTION[congestion].second }
                else { p.obfsPassword = obfs.trim(); p.serverPorts = hopPorts.trim() }
            }
            else -> { p.username = user.trim(); p.password = pass.trim() }
        }
        source?.let { s ->
            p.wsHost = s.wsHost; p.alterId = s.alterId; p.flow = s.flow; p.spiderX = s.spiderX; p.alpn = s.alpn; p.rawLink = s.rawLink
            // Not in the form: kept from the link or the saved server
            p.insecure = s.insecure; p.udpRelayMode = s.udpRelayMode
        }
        return p
    }
}

@Composable
fun ServerEditScreen(vm: MainViewModel) {
    val form = remember { Form() }
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var populating by remember { mutableStateOf(false) }

    LaunchedEffect(vm.editorRevision) {
        val p = vm.editingProxy
        if (p != null) {
            form.fill(p)
            form.quick = p.toShareableUrl()
        } else form.clear()
    }

    /** A field changed: the share link follows the form. */
    fun fieldsChanged() {
        if (populating) return
        form.build()?.takeIf { it.host.isNotBlank() }?.let { form.quick = it.toShareableUrl() }
    }

    /** The link changed: the form follows the link. */
    fun quickChanged(text: String) {
        form.quick = text
        val t = text.trim()
        if (ProxyParser.isSubscriptionUrl(t)) return
        ProxyParser.parseSingle(t)?.let {
            populating = true
            form.fill(it)
            form.quick = text
            populating = false
        }
    }

    fun importSubscription(url: String) {
        busy = true
        scope.launch {
            val r = vm.updateSubscription(url)
            busy = false
            if (r.ok) {
                vm.autoResolveMissingCountries()
                vm.saveConfig()
                vm.dialog = "Умная подписка" to "Серверы из подписки успешно импортированы и обновлены!"
                vm.navigate(AppScreen.Dashboard)
            } else vm.dialog = "Ошибка" to "Не удалось загрузить подписку: ${r.error}"
        }
    }

    fun paste() {
        val clip = vm.clipboardText()
        if (clip.isEmpty()) return
        val sub = ProxyParser.extractActualSubscriptionUrl(clip)
        if (ProxyParser.isSubscriptionUrl(sub)) {
            form.quick = sub
            importSubscription(sub)
            return
        }
        val bulk = ProxyParser.parseBulk(clip)
        if (bulk.size > 1) {
            vm.proxies.addAll(bulk)
            vm.selectProxy(bulk[0])
            vm.saveConfig()
            vm.dialog = "Пакетный импорт" to "Импортировано ${bulk.size} серверов!"
            vm.navigate(AppScreen.Dashboard)
        } else if (bulk.size == 1) {
            populating = true
            form.fill(bulk[0])
            form.quick = bulk[0].toShareableUrl()
            populating = false
        }
    }

    fun save() {
        val quick = form.quick.trim()
        val sub = ProxyParser.extractActualSubscriptionUrl(quick)
        if (ProxyParser.isSubscriptionUrl(sub)) {
            importSubscription(sub)
            return
        }
        if (form.host.isBlank()) {
            vm.dialog = "Ошибка валидации" to "Пожалуйста, укажите Хост или IP-адрес сервера."
            return
        }
        if (form.port.trim().toIntOrNull()?.takeIf { it in 1..65535 } == null) {
            vm.dialog = "Ошибка валидации" to "Укажите корректный порт сервера (от 1 до 65535)."
            return
        }
        val built = form.build() ?: return
        val editing = vm.editingProxy
        val target = if (editing != null) {
            val movedOrRenamed = !editing.cleanHost.equals(built.cleanHost, true) || editing.name != built.name
            editing.name = built.name; editing.host = built.host; editing.port = built.port; editing.protocol = built.protocol
            editing.uuid = built.uuid; editing.transportType = built.transportType; editing.security = built.security
            editing.fingerprint = built.fingerprint; editing.sni = built.sni; editing.publicKey = built.publicKey; editing.shortId = built.shortId
            editing.grpcServiceName = built.grpcServiceName; editing.wsPath = built.wsPath; editing.wsHost = built.wsHost
            editing.alterId = built.alterId; editing.flow = built.flow; editing.spiderX = built.spiderX; editing.alpn = built.alpn
            editing.username = built.username; editing.password = built.password; editing.obfsPassword = built.obfsPassword
            editing.serverPorts = built.serverPorts; editing.insecure = built.insecure; editing.congestionControl = built.congestionControl
            editing.udpRelayMode = built.udpRelayMode
            // Whatever the last check found was about the old settings
            editing.status = ProxyStatus.Unknown; editing.pingMs = -1; editing.workingAddress = null
            // A new name or address may mean another country: take it from the name again (GeoIP refines it below)
            if (movedOrRenamed || editing.country.isBlank()) {
                editing.country = ""
                editing.autoEnrichCountryIfMissing()
            }
            vm.selectProxy(editing)
            vm.saveConfig()
            editing
        } else {
            built.autoEnrichCountryIfMissing() // the flag shows at once, not only after GeoIP or a restart
            vm.addProxy(built)
            built
        }
        vm.scope.launch {
            val resolved = GeoIp.resolveCountryForHost(target.cleanHost)
            if (resolved.isNotBlank()) {
                target.country = resolved
                vm.saveConfig()
                vm.bump()
            }
        }
        vm.navigate(AppScreen.Dashboard)
    }

    Column(Modifier.fillMaxSize().padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 18.dp)) {
        ScreenHeader(vm.editFormTitle, { vm.navigate(AppScreen.ServerList) }, titleMaxWidth = 230.dp)

        // Quick import / export
        Glass(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(Modifier.padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Ссылка сервера", Z.text(12.5.sp, FontWeight.SemiBold), Modifier.weight(1f))
                if (copied) Txt("✓ Скопировано", Z.text(11.sp, FontWeight.SemiBold, Z.Success))
                if (busy) Txt("Загрузка…", Z.text(11.sp, FontWeight.SemiBold, Z.AccentHi))
            }
            Txt("Вставьте готовую ссылку или скопируйте текущую", Z.text(11.sp, color = Z.TextMuted), Modifier.padding(bottom = 9.dp))
            ZField(form.quick, ::quickChanged, "vless://, trojan://, ss://, vmess://, hysteria2://, tuic://, подписка https://...", Modifier.fillMaxWidth().padding(bottom = 8.dp))
            Row {
                TonalBtn("Вставить", ::paste, Modifier.weight(1f), height = 32.dp)
                Spacer(Modifier.width(8.dp))
                GhostBtn("Скопировать", {
                    val url = form.build()?.toShareableUrl() ?: form.quick.trim()
                    if (url.isNotBlank()) {
                        vm.copyToClipboard(url)
                        copied = true
                        scope.launch { delay(2000); copied = false }
                    }
                }, Modifier.weight(1f), height = 32.dp, size = 12f)
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            FieldLabel("Название")
            ZField(form.name, { form.name = it; fieldsChanged() }, "например: Германия #1 или США", Modifier.fillMaxWidth().padding(bottom = 10.dp))

            Row(Modifier.padding(bottom = 10.dp)) {
                Column(Modifier.weight(2.8f).padding(end = 8.dp)) {
                    FieldLabel("Хост / IP")
                    ZField(form.host, { form.host = it; fieldsChanged() }, "vpn.example.com", Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(1.4f).padding(end = 8.dp)) {
                    FieldLabel("Порт")
                    ZField(form.port, { form.port = it.filter(Char::isDigit); fieldsChanged() }, "443", Modifier.fillMaxWidth(), keyboard = KeyboardType.Number)
                }
                Column(Modifier.weight(2f)) {
                    FieldLabel("Протокол")
                    ZDropdown(PROTOCOLS.map { it.first }, form.protocol, { form.protocol = it; fieldsChanged() }, Modifier.fillMaxWidth())
                }
            }

            val proto = form.proto
            val vlessLike = proto == ProxyProtocol.Vless || proto == ProxyProtocol.Trojan || proto == ProxyProtocol.Vmess
            val quic = proto == ProxyProtocol.Hysteria2 || proto == ProxyProtocol.Tuic

            if (vlessLike) {
                FieldLabel("Ключ пользователя (UUID)")
                ZField(form.uuid, { form.uuid = it; fieldsChanged() }, "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx", Modifier.fillMaxWidth().padding(bottom = 10.dp))
                Glass(Modifier.fillMaxWidth().padding(bottom = 8.dp), padding = PaddingValues(12.dp, 12.dp, 12.dp, 4.dp)) {
                    Overline("СЕТЬ И МАСКИРОВКА", Modifier.padding(start = 2.dp, bottom = 10.dp), color = Z.AccentHi)
                    Row(Modifier.padding(bottom = 10.dp)) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            FieldLabel("Транспорт")
                            ZDropdown(TRANSPORTS.map { it.first }, form.transport, { form.transport = it; fieldsChanged() }, Modifier.fillMaxWidth())
                        }
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            FieldLabel("Безопасность")
                            val options = if (proto == ProxyProtocol.Vmess) SECURITY.drop(1) else SECURITY
                            val sel = if (proto == ProxyProtocol.Vmess) (form.security - 1).coerceAtLeast(0) else form.security
                            ZDropdown(options.map { it.first }, sel, { form.security = if (proto == ProxyProtocol.Vmess) it + 1 else it; fieldsChanged() }, Modifier.fillMaxWidth())
                        }
                        Column(Modifier.weight(1.1f)) {
                            FieldLabel("Отпечаток (FP)")
                            ZDropdown(FINGERPRINTS.map { it.first }, form.fingerprint, { form.fingerprint = it; fieldsChanged() }, Modifier.fillMaxWidth())
                        }
                    }
                    Row(Modifier.padding(bottom = 10.dp)) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            FieldLabel("Сайт маскировки (SNI)")
                            ZField(form.sni, { form.sni = it; fieldsChanged() }, "yahoo.com", Modifier.fillMaxWidth())
                        }
                        Column(Modifier.weight(1f)) {
                            FieldLabel("Публичный ключ (PBK)")
                            ZField(form.publicKey, { form.publicKey = it; fieldsChanged() }, "Public Key", Modifier.fillMaxWidth())
                        }
                    }
                    Row(Modifier.padding(bottom = 8.dp)) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            FieldLabel("Короткий ID (ShortId)")
                            ZField(form.shortId, { form.shortId = it; fieldsChanged() }, "Short ID", Modifier.fillMaxWidth())
                        }
                        Column(Modifier.weight(1f)) {
                            FieldLabel("Служба / Путь")
                            ZField(form.servicePath, { form.servicePath = it; fieldsChanged() }, "ServiceName / Path", Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            if (!vlessLike && !quic) {
                Overline("АВТОРИЗАЦИЯ (НЕОБЯЗАТЕЛЬНО)", Modifier.padding(start = 2.dp, top = 2.dp, bottom = 10.dp), color = Z.AccentHi)
                if (proto == ProxyProtocol.Shadowsocks) {
                    FieldLabel("Метод шифрования")
                    ZDropdown(SS_METHODS, form.ssMethod, { form.ssMethod = it; fieldsChanged() }, Modifier.fillMaxWidth().padding(bottom = 10.dp))
                }
                Row(Modifier.padding(bottom = 8.dp)) {
                    if (proto != ProxyProtocol.Shadowsocks) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            FieldLabel("Логин")
                            ZField(form.user, { form.user = it; fieldsChanged() }, "Логин", Modifier.fillMaxWidth())
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        FieldLabel("Пароль")
                        ZField(form.pass, { form.pass = it; fieldsChanged() }, "Пароль", Modifier.fillMaxWidth())
                    }
                }
            }

            if (quic) {
                Row(Modifier.padding(bottom = 10.dp)) {
                    if (proto == ProxyProtocol.Tuic) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            FieldLabel("Ключ пользователя (UUID)")
                            ZField(form.tuicUuid, { form.tuicUuid = it; fieldsChanged() }, "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx", Modifier.fillMaxWidth())
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        FieldLabel("Пароль")
                        ZField(form.quicPassword, { form.quicPassword = it; fieldsChanged() }, "Пароль", Modifier.fillMaxWidth())
                    }
                }
                Glass(Modifier.fillMaxWidth().padding(bottom = 8.dp), padding = PaddingValues(12.dp, 12.dp, 12.dp, 4.dp)) {
                    Overline("СОЕДИНЕНИЕ", Modifier.padding(start = 2.dp, bottom = 10.dp), color = Z.AccentHi)
                    Row(Modifier.padding(bottom = 10.dp)) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            FieldLabel("Сайт маскировки (SNI)")
                            ZField(form.quicSni, { form.quicSni = it; fieldsChanged() }, "example.com", Modifier.fillMaxWidth())
                        }
                        Column(Modifier.weight(1f)) {
                            if (proto == ProxyProtocol.Hysteria2) {
                                FieldLabel("Пароль обфускации")
                                ZField(form.obfs, { form.obfs = it; fieldsChanged() }, "Salamander", Modifier.fillMaxWidth())
                            } else {
                                FieldLabel("Контроль перегрузки")
                                ZDropdown(CONGESTION.map { it.first }, form.congestion, { form.congestion = it; fieldsChanged() }, Modifier.fillMaxWidth())
                            }
                        }
                    }
                    if (proto == ProxyProtocol.Hysteria2) {
                        FieldLabel("Диапазон портов")
                        ZField(form.hopPorts, { form.hopPorts = it; fieldsChanged() }, "20000-30000", Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        Row(Modifier.padding(top = 12.dp)) {
            GhostBtn("Отмена", { vm.navigate(AppScreen.ServerList) }, Modifier.weight(1f), height = 42.dp)
            Spacer(Modifier.width(10.dp))
            PrimaryBtn("Сохранить", ::save, Modifier.weight(1.6f), height = 42.dp)
        }
    }
}
