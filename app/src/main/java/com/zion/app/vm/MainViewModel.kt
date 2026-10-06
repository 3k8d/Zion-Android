package com.zion.app.vm

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zion.app.core.ConfigBuilder
import com.zion.app.core.ConfigStore
import com.zion.app.core.FailoverPlanner
import com.zion.app.core.Format
import com.zion.app.core.GeoIp
import com.zion.app.core.ProxyParser
import com.zion.app.core.SubscriptionFetch
import com.zion.app.core.SubscriptionService
import com.zion.app.core.SubscriptionUpdateResult
import com.zion.app.model.AppConfig
import com.zion.app.model.AppScreen
import com.zion.app.model.DnsProvider
import com.zion.app.model.ProxyItem
import com.zion.app.model.ProxyStatus
import com.zion.app.model.SubscriptionEntry
import com.zion.app.vpn.ConnectionController
import com.zion.app.vpn.ConnectionState
import com.zion.app.vpn.InternetProbe
import com.zion.app.vpn.ServerCheckOutcome
import com.zion.app.vpn.ServerCheckResult
import com.zion.app.vpn.ServerChecker
import com.zion.app.vpn.ZionVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's state and logic, one per process (the service and every screen share it). Same behaviour as
 * the Windows MainViewModel; the screens read [tick] so they redraw after anything changes.
 */
class MainViewModel(val app: Application) {
    val scope = MainScope()
    val controller = ConnectionController(app)
    val config: AppConfig get() = ConfigStore.config

    /** Bumped after any change the screens should show (servers are plain objects changed in place). */
    var tick by mutableIntStateOf(0)
        private set

    fun bump() {
        tick++
    }

    val proxies: MutableList<ProxyItem> = ArrayList(config.proxies)

    // Links being downloaded right now (one download per link at a time)
    private val subscriptionsUpdating = HashSet<String>()

    var selectedProxy: ProxyItem? = null
        private set

    var isConnected by mutableStateOf(false)
        private set
    var isConnecting by mutableStateOf(false)
        private set
    var connectionState by mutableStateOf(ConnectionState.Disconnected)
        private set

    var sessionTimerText by mutableStateOf("00:00:00")
        private set
    var totalTrafficText by mutableStateOf("0.00 GB")
        private set
    var downSpeedText by mutableStateOf("0.0 KB/s")
        private set
    var upSpeedText by mutableStateOf("0.0 KB/s")
        private set

    var currentScreen by mutableStateOf(AppScreen.Dashboard)
        private set
    var isDnsModalOpen by mutableStateOf(false)

    var isLinkDown by mutableStateOf(false)
        private set
    var toastText by mutableStateOf("")
        private set
    var isToastVisible by mutableStateOf(false)
        private set
    var copyLogsButtonText by mutableStateOf("Скопировать")
        private set

    /** A message box (title, text) the UI shows; null when none. */
    var dialog by mutableStateOf<Pair<String, String>?>(null)

    /** Android must ask the user before Zion may open a VPN: the activity launches this and reports back. */
    var vpnPermissionRequest by mutableStateOf<Intent?>(null)
        private set

    var editingProxy: ProxyItem? = null
        private set
    /** Bumped each time the server form is opened, so it fills itself again. */
    var editorRevision by mutableIntStateOf(0)
        private set

    // Toggles (saved; changes that shape the tunnel restart it)
    var autoConnectOnStartup by mutableStateOf(config.autoConnectOnStartup); private set
    var bypassTorrents by mutableStateOf(config.bypassTorrents); private set
    var bypassDomesticRu by mutableStateOf(config.bypassDomesticRu); private set
    var selectedDns by mutableStateOf(config.selectedDns); private set
    var autoServerFailover by mutableStateOf(config.autoServerFailover); private set
    var autoUpdateSubscription by mutableStateOf(config.autoUpdateSubscription); private set
    var blockQuic by mutableStateOf(config.blockQuic); private set
    var blockWebRtc by mutableStateOf(config.blockWebRtc); private set
    var blockTrackers by mutableStateOf(config.blockTrackers); private set
    var killSwitch by mutableStateOf(config.killSwitch); private set

    val serverList = ServerListState(this)
    val subscriptions = SubscriptionsState(this)
    val excludedApps = ExcludedAppsState(this)
    val excludedSites = ExcludedSitesState(this)

    private val logEntries = ArrayDeque<String>()
    private var userWantsConnection = false
    private var failoverInProgress = false
    private var lastFailoverFinished = 0L
    private var connectedStartTime = 0L
    private var timerJob: Job? = null
    private var toastJob: Job? = null
    private var routingReconnectJob: Job? = null
    private var pendingConnectAfterPermission = false

    val hwid: String by lazy {
        val id = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: "zion"
        MessageDigest.getInstance("MD5").digest(id.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    init {
        selectedProxy = config.selectedProxyId?.let { id -> proxies.firstOrNull { it.id == id } } ?: proxies.firstOrNull()
        controller.killSwitchEnabled = killSwitch
        applyControllerRouting()
        wireController()

        // Startup country resolution
        scope.launch {
            delay(2000)
            autoResolveMissingCountries()
        }
        // Automatic subscription refresh: shortly after start-up (auto-connect goes first, so the download can
        // use the tunnel), then an hourly check whether a day has passed
        scope.launch {
            delay(15_000)
            while (isActive) {
                refreshAllSubscriptions(automatic = true, onlyDue = true)
                delay(60 * 60 * 1000L)
            }
        }
    }

    private fun markSelected(p: ProxyItem?) {
        selectedProxy = p
    }

    // ====================================================================== status texts

    val statusDotColor: Long get() = when { isConnected -> 0xFF34E0A1; isConnecting -> 0xFFFBBF24; else -> 0xFF8B7DFF }
    val outerHaloBg: Long get() = when { isConnected -> 0xFF0E3B2C; isConnecting -> 0xFF3A2A08; else -> 0xFF1D1A45 }
    val outerHaloBorder: Long get() = when { isConnected -> 0xFF34E0A1; isConnecting -> 0xFFFBBF24; else -> 0xFF8B7DFF }
    val powerBtnTop: Long get() = when { isConnected -> 0xFF4BEBB0; isConnecting -> 0xFFFCD34D; else -> 0xFF9D8FFF }
    val powerBtnBottom: Long get() = when { isConnected -> 0xFF0C9B6B; isConnecting -> 0xFFD97706; else -> 0xFF5B4BE8 }
    val powerBtnRingStroke: Long get() = when { isConnected -> 0xFF8AF7D0; isConnecting -> 0xFFFDE68A; else -> 0xFFB9B0FF }

    val powerStatusTextColor: Long
        get() {
            if (connectionState == ConnectionState.Connected && isLinkDown) return 0xFFFB7185
            return when (connectionState) {
                ConnectionState.Failed -> 0xFFFB7185
                ConnectionState.Starting, ConnectionState.Validating, ConnectionState.Reconnecting -> 0xFFFBBF24
                else -> 0xFFA3ACC2
            }
        }

    val powerPromptText: String
        get() {
            if (connectionState == ConnectionState.Connected && isLinkDown)
                return if (controller.isInternetDown) "Нет интернета" else "Нет связи с сервером"
            if (connectionState == ConnectionState.Failed && controller.isKillSwitchBlocking) return "Kill Switch: интернет заблокирован"
            return when (connectionState) {
                ConnectionState.Starting -> "Инициализация..."
                ConnectionState.Validating -> "Проверка конфигурации..."
                ConnectionState.Reconnecting -> "Переподключение..."
                ConnectionState.Connected -> "Нажмите для отключения"
                ConnectionState.Stopping -> "Отключение..."
                ConnectionState.Failed -> "Ошибка подключения"
                else -> "Нажмите для подключения"
            }
        }

    val serverCardSubtitle: String get() = selectedProxy?.cleanName ?: "Выберите сервер"
    val serverCardBadge: String get() = selectedProxy?.protocolBadge ?: "VLESS"
    val serverCardCountry: String get() = selectedProxy?.country ?: ""

    val dnsBadgeText: String
        get() = when (selectedDns) {
            DnsProvider.Cloudflare -> "Cloudflare"
            DnsProvider.Google -> "Google"
            DnsProvider.ControlD -> "Control D"
            DnsProvider.NextDns -> "NextDNS"
            DnsProvider.AdGuard -> "AdGuard"
            DnsProvider.OpenDNS -> "OpenDNS"
            DnsProvider.CleanBrowsing -> "CleanBrowsing"
            else -> "Авто"
        }

    val dnsSubtitleText: String
        get() {
            if (selectedDns == DnsProvider.Auto) {
                val active = controller.activeDnsName
                return if (isConnected && active.isNotEmpty()) "Работает через: $active" else "Автоматический выбор"
            }
            return "Прямой выбор"
        }

    val dnsSubtitleColor: Long get() = if (selectedDns == DnsProvider.Auto && isConnected) 0xFFB3A9FF else 0xFFE6E9F2

    val dnsOptions = listOf(
        DnsProvider.Auto to "Авто", DnsProvider.Cloudflare to "Cloudflare", DnsProvider.Google to "Google",
        DnsProvider.ControlD to "Control D", DnsProvider.NextDns to "NextDNS", DnsProvider.AdGuard to "AdGuard",
        DnsProvider.OpenDNS to "OpenDNS", DnsProvider.CleanBrowsing to "CleanBrowsing",
    )

    fun notificationText(): String = when {
        isConnected -> "Подключено · ${selectedProxy?.cleanName ?: ""}"
        controller.isKillSwitchBlocking -> "Kill Switch: интернет заблокирован"
        else -> "Подключение…"
    }

    // ====================================================================== navigation

    fun navigate(screen: AppScreen) {
        if (currentScreen == screen) return
        currentScreen = screen
        // Opening the list checks the servers, at most every few minutes (new servers right away)
        if (screen == AppScreen.ServerList && isAutoCheckDue()) scope.launch { checkServers() }
    }

    /** Android back: the same place the screen's own back button leads to. Returns false on the dashboard. */
    fun back(): Boolean {
        when {
            isDnsModalOpen -> isDnsModalOpen = false
            serverList.isDeleteAllConfirmOpen -> serverList.isDeleteAllConfirmOpen = false
            excludedApps.isPickerOpen -> excludedApps.isPickerOpen = false
            dialog != null -> dialog = null
            else -> when (currentScreen) {
                AppScreen.Dashboard -> return false
                AppScreen.ServerEdit, AppScreen.Subscriptions -> navigate(AppScreen.ServerList)
                AppScreen.Exclusions, AppScreen.ExcludedSites -> navigate(AppScreen.Settings)
                else -> navigate(AppScreen.Dashboard)
            }
        }
        return true
    }

    /** Opens the server form for an existing server, or empty for a new one. */
    fun openEditor(proxy: ProxyItem?) {
        editingProxy = proxy
        editorRevision++
        navigate(AppScreen.ServerEdit)
    }

    val editFormTitle: String get() = if (editingProxy != null) "РЕДАКТИРОВАНИЕ СЕРВЕРА" else "ДОБАВЛЕНИЕ СЕРВЕРА"

    // ====================================================================== toggles

    fun setAutoConnect(v: Boolean) { autoConnectOnStartup = v; saveConfig() }
    fun setAutoFailover(v: Boolean) { autoServerFailover = v; saveConfig() }
    fun setAutoUpdate(v: Boolean) {
        autoUpdateSubscription = v
        saveConfig()
        if (v) scope.launch { refreshAllSubscriptions(automatic = true, onlyDue = true) }
    }
    fun changeBypassTorrents(v: Boolean) { bypassTorrents = v; saveConfig(); reconnectIfConnected() }
    fun setBypassRu(v: Boolean) { bypassDomesticRu = v; saveConfig(); reconnectIfConnected() }
    fun setQuic(v: Boolean) { blockQuic = v; saveConfig(); reconnectIfConnected() }
    fun setWebRtc(v: Boolean) { blockWebRtc = v; saveConfig(); reconnectIfConnected() }
    fun setTrackers(v: Boolean) { blockTrackers = v; saveConfig(); reconnectIfConnected() }

    fun selectDns(p: DnsProvider) {
        isDnsModalOpen = false
        if (selectedDns == p) return
        selectedDns = p
        saveConfig()
        reconnectIfConnected()
    }

    fun changeKillSwitch(v: Boolean) {
        killSwitch = v
        saveConfig()
        controller.killSwitchEnabled = v
        if (!v) {
            // Turning it off is the user's way back to direct internet after a failure
            controller.disarmKillSwitch()
        } else {
            showToast("Для защиты и при закрытом Zion включите в настройках Android «Постоянная VPN» и «Блокировать соединения без VPN».")
            if (userWantsConnection) scope.launch {
                if (!controller.armKillSwitchIfEnabled()) showToast("Kill Switch не включился: нет разрешения на VPN.")
                bump()
            }
        }
        bump()
    }

    private fun reconnectIfConnected() {
        if (isConnected) scope.launch { reconnectWithCurrentConfig() }
        bump()
    }

    private fun args() = ConnectionController.Args(bypassTorrents, bypassDomesticRu, selectedDns, blockQuic, blockWebRtc, blockTrackers)

    private suspend fun reconnectWithCurrentConfig() {
        val p = selectedProxy ?: return
        if (!isConnected) return
        controller.reconnect(p, args())
    }

    /** Hands the excluded apps and sites to the core and restarts a running tunnel to apply them. */
    fun applyRoutingExceptions() {
        saveConfig()
        applyControllerRouting()
        if (isConnected) {
            // Several apps or sites are often added one after another: restart once, after a short pause
            routingReconnectJob?.cancel()
            routingReconnectJob = scope.launch {
                delay(2000)
                reconnectWithCurrentConfig()
            }
        }
    }

    private fun applyControllerRouting() {
        controller.directApps = ConfigBuilder.sanitizeDirectApps(config.directApps.map { it.packageName }, app.packageName)
        controller.directSites = com.zion.app.core.DirectSites.sanitize(config.directSites)
    }

    // ====================================================================== toast, logs

    fun showToast(text: String) {
        toastText = text
        isToastVisible = true
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(20_000)
            isToastVisible = false
        }
    }

    fun dismissToast() {
        isToastVisible = false
    }

    fun addLog(line: String) {
        if (logEntries.size > 400) logEntries.removeFirst()
        logEntries.addLast("[${SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())}] $line")
    }

    fun copyLogs() {
        copyLogsButtonText = if (logEntries.isEmpty()) "Журнал пуст" else try {
            val header = "Zion — журнал событий, ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT).format(Date())}\n"
            copyToClipboard(header + logEntries.joinToString("\n"))
            "Скопировано"
        } catch (_: Exception) {
            "Не удалось"
        }
        scope.launch {
            delay(1800)
            copyLogsButtonText = "Скопировать"
        }
    }

    fun copyToClipboard(text: String) {
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Zion", text))
    }

    fun clipboardText(): String =
        app.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()?.trim() ?: ""

    // ====================================================================== connection

    private fun wireController() {
        controller.onStateChanged = { state, err -> scope.launch { onState(state, err) } }
        controller.onDnsSwitched = { from, to ->
            scope.launch {
                showToast(if (from == to) "DNS $from не отвечал. Переключено на его запасной адрес." else "DNS $from не отвечал. Переключено на $to.")
            }
        }
        controller.onDnsUnavailable = { name -> scope.launch { showToast("DNS $name не отвечает. Выберите «Авто» в настройках DNS.") } }
        controller.onLinkHealthChanged = { healthy ->
            scope.launch {
                if (!isConnected) return@launch
                isLinkDown = !healthy
                bump()
                // No switching while the network itself is down: the tunnel comes back on its own
                if (!healthy && !controller.isInternetDown && canAutoFailover()) runFailover()
            }
        }
        controller.onTraffic = { up, down ->
            scope.launch {
                if (isConnected) {
                    downSpeedText = Format.speed(down)
                    upSpeedText = Format.speed(up)
                }
            }
        }
        controller.onProxiedTotal = { bytes -> scope.launch { if (isConnected) totalTrafficText = Format.traffic(bytes) } }
        controller.onLog = { line -> scope.launch { addLog(line) } }
    }

    private fun onState(state: ConnectionState, err: String?) {
        connectionState = state
        isConnected = state == ConnectionState.Connected
        isConnecting = state == ConnectionState.Starting || state == ConnectionState.Validating ||
            state == ConnectionState.Reconnecting || state == ConnectionState.Stopping

        if (state == ConnectionState.Connected) {
            connectedStartTime = controller.connectedStartTime ?: System.currentTimeMillis()
            totalTrafficText = "0.00 GB"
            sessionTimerText = "00:00:00"
            startTimer()
            if (killSwitch && !controller.isKillSwitchBlocking && ZionVpnService.instance == null) {
                showToast("Kill Switch не включился: нет разрешения на VPN.")
            }
        } else if (state == ConnectionState.Disconnected || state == ConnectionState.Failed) {
            timerJob?.cancel()
            downSpeedText = "0.0 KB/s"
            upSpeedText = "0.0 KB/s"
        }
        if (state != ConnectionState.Connected) isLinkDown = false

        if (state == ConnectionState.Failed) {
            if (!err.isNullOrEmpty()) addLog("Подключение не удалось: $err")
            if (canAutoFailover()) scope.launch { runFailover(err) }
            else if (!failoverInProgress && !err.isNullOrEmpty()) dialog = "Ошибка соединения" to withKillSwitchHint(err)
        }
        ZionVpnService.instance?.updateNotification(notificationText())
        bump()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive && isConnected) {
                sessionTimerText = Format.duration(System.currentTimeMillis() - connectedStartTime)
                delay(1000)
            }
        }
    }

    fun toggleConnection() {
        if (isConnecting) return
        if (isConnected) disconnect() else connect()
    }

    fun connect() {
        val proxy = selectedProxy
        if (proxy == null) {
            // Nothing to connect to (e.g. the list was emptied): show where servers are added
            navigate(AppScreen.ServerList)
            return
        }
        val permission = VpnService.prepare(app)
        if (permission != null) {
            pendingConnectAfterPermission = true
            vpnPermissionRequest = permission
            return
        }
        userWantsConnection = true
        controller.connect(proxy, args())
    }

    /** The activity reports Android's answer to the VPN permission dialog. */
    fun onVpnPermissionResult(granted: Boolean) {
        vpnPermissionRequest = null
        val wanted = pendingConnectAfterPermission
        pendingConnectAfterPermission = false
        if (!wanted) return
        if (granted) connect()
        else dialog = "Ошибка соединения" to "Без разрешения Android на VPN Zion не может подключиться. Нажмите кнопку ещё раз и разрешите подключение."
    }

    /** Android started the service itself (always-on VPN). */
    fun connectFromSystem() {
        scope.launch { if (!isConnected && !isConnecting) connect() }
    }

    fun disconnect() {
        userWantsConnection = false
        isLinkDown = false
        controller.disconnect()
    }

    fun onVpnRevoked() {
        userWantsConnection = false
        controller.onRevoked()
    }

    /** Called once the UI is up: auto-connect if the user turned it on. */
    fun onAppStarted() {
        if (autoConnectOnStartup && selectedProxy != null && !isConnected && !isConnecting && VpnService.prepare(app) == null) {
            scope.launch {
                delay(800)
                if (!isConnected && !isConnecting) connect()
            }
        }
    }

    fun setTrafficVisible(visible: Boolean) {
        controller.trafficVisible = visible
    }

    fun selectProxy(proxy: ProxyItem) {
        val wasConnected = isConnected
        markSelected(proxy)
        saveConfig()
        bump()
        if (wasConnected) scope.launch { controller.reconnect(proxy, args()) }
    }

    fun addProxy(proxy: ProxyItem) {
        proxies += proxy
        selectProxy(proxy)
    }

    fun deleteProxy(proxy: ProxyItem) {
        if (proxies.size <= 1) return
        val isCurrent = selectedProxy?.id == proxy.id
        proxies.remove(proxy)
        if (isCurrent) proxies.firstOrNull()?.let { selectProxy(it) } else saveConfig()
        bump()
    }

    // ====================================================================== automatic failover

    private fun canAutoFailover() = autoServerFailover && userWantsConnection && !failoverInProgress && proxies.size > 1 &&
        System.currentTimeMillis() - lastFailoverFinished > 3000

    private fun withKillSwitchHint(message: String) =
        if (controller.isKillSwitchBlocking) "$message\n\nKill Switch активен: интернет заблокирован, пока вы не подключитесь снова или не выключите Kill Switch."
        else message

    /** Walks the fallback list (same country first) until one server connects; Kill Switch stays armed meanwhile. */
    private suspend fun runFailover(originalError: String? = null) {
        if (failoverInProgress) return
        failoverInProgress = true
        try {
            val failed = selectedProxy
            val failedName = failed?.cleanName ?: "Сервер"

            // Another server cannot help when the network itself is down
            if (InternetProbe.isInternetReachable() == false) {
                addLog("Автопереключение отменено: нет интернета.")
                if (userWantsConnection && !isConnected) {
                    dialog = "Ошибка соединения" to withKillSwitchHint("Нет подключения к интернету. Сервер не менялся: подключитесь снова, когда сеть вернётся.")
                }
                return
            }

            val candidates = FailoverPlanner.orderCandidates(failed, proxies, max = 8)
            addLog("Автопереключение: «$failedName» недоступен, проверяем кандидатов: ${candidates.size}.")

            // Check the candidates for real first: a dead server is skipped at once instead of costing a whole attempt
            val checks = ServerChecker(app).check(candidates)
            checks.forEach { applyCheckResult(it) }
            bump()
            val dead = checks.filter { it.outcome == ServerCheckOutcome.NotWorking }.map { it.server }.toHashSet()
            var attempts = FailoverPlanner.orderCandidates(failed, candidates, max = candidates.size).filter { it !in dead }
            if (attempts.isEmpty() && candidates.isNotEmpty()) {
                addLog("Автопереключение: ни один кандидат не прошёл проверку, пробуем два лучших напрямую.")
                attempts = candidates.take(2)
            }

            for (next in attempts) {
                if (!userWantsConnection) return
                addLog("Автопереключение: пробуем «${next.cleanName}».")
                markSelected(next)
                bump()
                if (controller.reconnect(next, args())) {
                    saveConfig()
                    showToast("«$failedName» не отвечает. Переключено на «${next.cleanName}».")
                    return
                }
            }

            if (userWantsConnection) {
                // Nothing worked: leave the user's own choice selected rather than the last thing we tried
                if (failed != null && failed in proxies) markSelected(failed)
                val details = if (originalError.isNullOrBlank()) "" else "\n\nПервая ошибка: $originalError"
                dialog = "Ошибка соединения" to withKillSwitchHint("Не удалось подключиться ни к одному из серверов. Проверьте интернет или обновите подписку.$details")
            }
        } finally {
            failoverInProgress = false
            lastFailoverFinished = System.currentTimeMillis()
            bump()
        }
    }

    // ====================================================================== server check

    private var lastFullCheck = 0L
    private val checkedServers = HashSet<ProxyItem>()

    var isCheckingServers by mutableStateOf(false)
        private set
    private var checkDone = 0
    private var checkTotal = 0
    var checkProgressText by mutableStateOf("0/0")
        private set

    private fun isAutoCheckDue() = !isCheckingServers &&
        (System.currentTimeMillis() - lastFullCheck > 10 * 60 * 1000L || proxies.any { !it.isDivider && it !in checkedServers })

    /** Checks every server for real; each card is updated the moment its result is in. One check at a time. */
    suspend fun checkServers() {
        if (isCheckingServers) return
        val targets = proxies.filter { !it.isDivider }
        if (targets.isEmpty()) return
        checkDone = 0
        checkTotal = targets.size
        checkProgressText = "0/$checkTotal"
        isCheckingServers = true
        try {
            val results = ServerChecker(app).check(targets) { r ->
                scope.launch {
                    applyCheckResult(r)
                    checkDone++
                    checkProgressText = "$checkDone/$checkTotal"
                    bump()
                }
            }
            checkedServers += targets
            checkedServers.retainAll(proxies.toSet())
            lastFullCheck = System.currentTimeMillis()
            val working = results.count { it.outcome == ServerCheckOutcome.Working }
            val unknown = results.filter { it.outcome == ServerCheckOutcome.Unknown }
            addLog(if (unknown.size == results.size && results.isNotEmpty()) "Проверка серверов не удалась: ${unknown[0].detail}"
                   else "Проверка серверов: работают $working из ${results.size}.")
        } catch (e: Exception) {
            addLog("Проверка серверов не удалась: ${e.message}")
        } finally {
            isCheckingServers = false
            bump()
        }
    }

    /** Puts a check result on the server's card. "Unknown" (the check could not run) changes nothing. */
    private fun applyCheckResult(r: ServerCheckResult) {
        val p = r.server
        when (r.outcome) {
            ServerCheckOutcome.Working -> {
                p.status = ProxyStatus.Online
                p.pingMs = r.delayMs
                p.workingAddress = r.address // the tunnel connects straight to it
            }
            ServerCheckOutcome.NotWorking -> {
                p.status = ProxyStatus.Offline
                p.pingMs = -1
                p.workingAddress = null
            }
            ServerCheckOutcome.Unknown -> Unit
        }
    }

    // ====================================================================== countries

    fun autoResolveMissingCountries() {
        val unresolved = proxies.filter { it.country.isEmpty() || it.country == it.cleanHost }
        if (unresolved.isEmpty()) return
        scope.launch {
            for (proxy in unresolved) {
                val country = GeoIp.resolveCountryForHost(proxy.cleanHost)
                if (country.isNotEmpty()) {
                    proxy.country = country
                    bump()
                }
                delay(200)
            }
            saveConfig()
        }
    }

    // ====================================================================== subscriptions

    fun isSubscriptionUpdating(url: String) = subscriptionsUpdating.any { SubscriptionService.sameUrl(it, url) }

    /** Downloads one subscription (adding it if the link is new) and merges it into the list. Never throws. */
    suspend fun updateSubscription(rawUrl: String, automatic: Boolean = false): SubscriptionUpdateResult {
        val url = ProxyParser.extractActualSubscriptionUrl(rawUrl).trim()
        if (url.isBlank()) return SubscriptionUpdateResult.failed("Пустая ссылка")
        if (!subscriptionsUpdating.add(url)) return SubscriptionUpdateResult.failed("Уже обновляется")
        subscriptions.sync()
        val name = Format.subscriptionName(SubscriptionService.find(config, url)?.title, url)
        try {
            val fetched = withContext(Dispatchers.IO) { SubscriptionFetch.fetch(url, hwid, tunnelUp = isConnected) }
            val result = SubscriptionService.apply(url, fetched, proxies, config)
            if (result.ok) {
                ensureValidSelection()
                autoResolveMissingCountries()
                saveConfig()
                addLog("${if (automatic) "Автообновление" else "Обновление"} подписки «$name»: серверов ${result.total} (+${result.added}, −${result.removed}).")
            } else {
                saveConfig()
                addLog("Подписка «$name» не обновилась: ${result.error}.${if (automatic) " Повторим через час." else ""}")
            }
            return result
        } catch (e: Exception) {
            addLog("Ошибка обновления подписки «$name»: ${e.message}")
            return SubscriptionUpdateResult.failed(e.message ?: "ошибка")
        } finally {
            subscriptionsUpdating.remove(url)
            subscriptions.sync()
            bump()
        }
    }

    /** Refreshes every subscription (or only those due for the daily update). Returns how many succeeded. */
    suspend fun refreshAllSubscriptions(automatic: Boolean = false, onlyDue: Boolean = false): Int {
        var ok = 0
        for (entry in config.subscriptions.toList()) {
            if (onlyDue && !SubscriptionService.isAutoUpdateDue(config, entry, System.currentTimeMillis())) continue
            if (updateSubscription(entry.url, automatic).ok) ok++
        }
        return ok
    }

    class RemovedSubscription(val entry: SubscriptionEntry, val servers: ServerListSnapshot, val memberIds: List<String>, val removedCount: Int)

    /** Forgets a subscription and its servers. The server the VPN is using stays (as a manual one). */
    fun removeSubscription(entry: SubscriptionEntry): RemovedSubscription {
        val name = Format.subscriptionName(entry.title, entry.url)
        val memberIds = proxies.filter { it.isFromSubscription && SubscriptionService.sameUrl(it.subscriptionUrl, entry.url) }.map { it.id }
        val snapshot = ServerListSnapshot(proxies.toList(), memberIds.size, selectedProxy?.id)
        val removed = SubscriptionService.removeSubscription(entry, proxies, config, serverInUse)
        ensureValidSelection()
        saveConfig()
        addLog("Подписка «$name» удалена вместе с серверами ($removed).")
        subscriptions.sync()
        bump()
        return RemovedSubscription(entry, snapshot.copy(removedCount = removed), memberIds, removed)
    }

    /** Undo for [removeSubscription]: the link and its servers come back where they were. */
    fun restoreSubscription(removed: RemovedSubscription) {
        if (SubscriptionService.find(config, removed.entry.url) == null) config.subscriptions += removed.entry
        restoreProxies(removed.servers)
        val members = removed.memberIds.toHashSet()
        proxies.filter { it.id in members }.forEach {
            it.isFromSubscription = true
            it.subscriptionUrl = removed.entry.url
        }
        saveConfig()
        subscriptions.sync()
        bump()
    }

    /** After the list changed under us: if the selected server is gone, pick the closest replacement. */
    private fun ensureValidSelection() {
        val current = selectedProxy
        if (current != null && current in proxies) return
        val next = FailoverPlanner.orderCandidates(current, proxies, max = 1).firstOrNull()
        if (next == null) {
            markSelected(null)
            return
        }
        if (current != null && isConnected) {
            showToast("«${current.cleanName}» больше нет в подписке. Переключено на «${next.cleanName}».")
            selectProxy(next) // reconnects
        } else markSelected(next)
    }

    // ====================================================================== delete all (with undo)

    data class ServerListSnapshot(val originalOrder: List<ProxyItem>, val removedCount: Int, val selectedId: String?)

    /** The server the tunnel is using right now, or null when the VPN is off. */
    val serverInUse: ProxyItem? get() = if (isConnected || isConnecting || userWantsConnection) selectedProxy else null

    /** Servers "delete all" would remove: everything except the one in use. */
    val deletableProxyCount: Int get() = proxies.count { it !== serverInUse }

    fun deleteAllProxies(): ServerListSnapshot {
        val keep = serverInUse
        val original = proxies.toList()
        val snapshot = ServerListSnapshot(original, original.count { it !== keep }, selectedProxy?.id)
        proxies.retainAll { it === keep }
        markSelected(keep)
        saveConfig()
        addLog(if (keep == null) "Удалены все серверы (${snapshot.removedCount})."
               else "Удалены все серверы (${snapshot.removedCount}), кроме текущего «${keep.cleanName}».")
        bump()
        return snapshot
    }

    /** Puts back what was removed in the original order; anything added since stays at the end. */
    fun restoreProxies(snapshot: ServerListSnapshot) {
        val originalIds = snapshot.originalOrder.map { it.id }.toHashSet()
        val addedSince = proxies.filter { it.id !in originalIds }
        val stillPresent = proxies.groupBy { it.id }.mapValues { it.value.first() }
        proxies.clear()
        snapshot.originalOrder.forEach { proxies += stillPresent[it.id] ?: it }
        proxies += addedSince
        if (selectedProxy == null || selectedProxy !in proxies) {
            markSelected(proxies.firstOrNull { it.id == snapshot.selectedId } ?: proxies.firstOrNull())
        }
        saveConfig()
        addLog("Удаление отменено: возвращено серверов ${snapshot.removedCount}.")
        bump()
    }

    // ====================================================================== saving

    fun saveConfig() {
        val c = config
        c.proxies = proxies.toMutableList()
        c.selectedProxyId = selectedProxy?.id
        c.autoConnectOnStartup = autoConnectOnStartup
        c.autoServerFailover = autoServerFailover
        c.bypassTorrents = bypassTorrents
        c.bypassDomesticRu = bypassDomesticRu
        c.selectedDns = selectedDns
        c.autoUpdateSubscription = autoUpdateSubscription
        c.blockQuic = blockQuic
        c.blockWebRtc = blockWebRtc
        c.blockTrackers = blockTrackers
        c.killSwitch = killSwitch
        ConfigStore.save(c)
    }

    /** "Сайт маскировки" etc. are typed in a form; the share link of a parsed or saved server keeps the rest. */
    fun isSelected(p: ProxyItem) = p === selectedProxy
}
