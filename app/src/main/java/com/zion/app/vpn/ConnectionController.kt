package com.zion.app.vpn

import android.content.Context
import com.zion.app.core.ClashApi
import com.zion.app.core.DnsConstants
import com.zion.app.core.DohTarget
import com.zion.app.core.ProxiedTrafficCounter
import com.zion.app.core.TunnelSettings
import com.zion.app.core.isIpLiteral
import com.zion.app.model.DnsProvider
import com.zion.app.model.ProxyItem
import com.zion.app.model.ProxyStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.coroutineContext

enum class ConnectionState { Disconnected, Starting, Validating, Connected, Reconnecting, Stopping, Failed }

/**
 * The connection state machine (same as Windows): sessions, cancellation of stale attempts, the link health
 * monitor, DNS failover, traffic counters and Kill Switch. Events come in on background threads.
 */
class ConnectionController(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processLock = Mutex()
    private val tun = TunnelEngine(app)
    private val sessionSync = Any()

    private var activeSessionJob: Job? = null
    @Volatile private var currentSessionId = ""

    @Volatile var state = ConnectionState.Disconnected
        private set
    @Volatile var activeProxy: ProxyItem? = null
        private set
    var lastError = ""
        private set
    val activeDnsName: String get() = tun.activeDnsName
    @Volatile var connectedStartTime: Long? = null
        private set

    var onStateChanged: (ConnectionState, String?) -> Unit = { _, _ -> }
    var onTraffic: (up: Long, down: Long) -> Unit = { _, _ -> }
    /** Bytes (up + down) that went through the VPN server in the current session. */
    var onProxiedTotal: (Long) -> Unit = {}
    var onLog: (String) -> Unit = {}
    /** The server stopped answering (false) or came back (true) while the tunnel is up. */
    var onLinkHealthChanged: (Boolean) -> Unit = {}
    /** The DNS server stopped answering and the tunnel is moving to another one (from, to). */
    var onDnsSwitched: (String, String) -> Unit = { _, _ -> }
    /** The chosen DNS provider does not answer through the VPN and has no working address left. */
    var onDnsUnavailable: (String) -> Unit = {}

    /** When true, traffic outside the tunnel is blocked from the moment a connection is requested. */
    @Volatile var killSwitchEnabled = false

    /** Package names whose traffic bypasses the VPN; applied on the next (re)connect. */
    @Volatile var directApps: List<String> = emptyList()
    /** Sites (with subdomains) that bypass the VPN; applied on the next (re)connect. */
    @Volatile var directSites: List<String> = emptyList()

    @Volatile var isLinkHealthy = true
        private set
    /** Set together with an unhealthy link when the cause is the network itself, not the server. */
    @Volatile var isInternetDown = false
        private set

    /** True while Kill Switch holds the internet closed (no tunnel, nothing passes). */
    val isKillSwitchBlocking: Boolean get() = ZionVpnService.instance?.isBlocking == true

    private var healthJob: Job? = null
    private var trafficJob: Job? = null
    @Volatile private var lastDownloadAt = 0L
    @Volatile var trafficVisible = true

    /** What the current tunnel was started with, so a DNS switch can restart it identically. */
    private data class ConnectArgs(val proxy: ProxyItem, val bypassTorrents: Boolean, val bypassDomesticRu: Boolean, val dns: DnsProvider,
                                   val blockQuic: Boolean, val blockWebRtc: Boolean, val blockTrackers: Boolean)
    @Volatile private var lastArgs: ConnectArgs? = null

    /** DNS server proven to work through the VPN; used first on the next (re)start. */
    @Volatile private var preferredDns: DohTarget? = null

    init {
        tun.onLog = { onLog(it) }
    }

    /**
     * Arms the Kill Switch if the user enabled it: the blocking interface goes up before anything touches
     * the network. Returns false if Android would not let Zion block (no VPN permission).
     */
    suspend fun armKillSwitchIfEnabled(): Boolean {
        if (!killSwitchEnabled) return false
        if (tun.isActive || isKillSwitchBlocking) return true
        return try {
            ZionVpnService.ensureRunning(app)
            val ok = ZionVpnService.instance?.holdBlocker() == true
            onLog(if (ok) "Kill Switch включён: трафик мимо туннеля заблокирован." else "Kill Switch не удалось включить: нет разрешения на VPN.")
            ok
        } catch (e: Exception) {
            onLog("Kill Switch не удалось включить: ${e.message}")
            false
        }
    }

    fun disarmKillSwitch() {
        val service = ZionVpnService.instance ?: return
        if (!service.isBlocking) return
        service.releaseBlocker()
        if (!tun.isActive) service.shutdown()
        onLog("Kill Switch снят: прямой доступ в интернет восстановлен.")
    }

    private fun newSession(): String = synchronized(sessionSync) {
        activeSessionJob?.cancel()
        val id = UUID.randomUUID().toString()
        currentSessionId = id
        id
    }

    private fun setSessionState(newState: ConnectionState, sessionId: String, error: String? = null) {
        synchronized(sessionSync) {
            if (sessionId.isEmpty() || sessionId != currentSessionId) return // stale session: ignored
            state = newState
            if (!error.isNullOrEmpty()) lastError = error
        }
        onStateChanged(newState, error)
    }

    private fun resetState(newState: ConnectionState, error: String? = null) {
        synchronized(sessionSync) {
            state = newState
            if (!error.isNullOrEmpty()) lastError = error
        }
        onStateChanged(newState, error)
    }

    fun connect(proxy: ProxyItem, a: Args): Job? {
        if (state == ConnectionState.Connected && activeProxy?.id == proxy.id) return null
        val sessionId = newSession()
        stopHealthMonitor()
        preferredDns = null // a fresh connection starts with a fresh DNS choice
        lastArgs = ConnectArgs(proxy, a.bypassTorrents, a.bypassDomesticRu, a.dns, a.blockQuic, a.blockWebRtc, a.blockTrackers)
        val job = scope.launch {
            armKillSwitchIfEnabled()
            setSessionState(ConnectionState.Starting, sessionId)
            runSession(proxy, sessionId)
        }
        synchronized(sessionSync) { activeSessionJob = job }
        return job
    }

    /** Settings that shape the tunnel, from the UI. */
    data class Args(val bypassTorrents: Boolean, val bypassDomesticRu: Boolean, val dns: DnsProvider,
                    val blockQuic: Boolean, val blockWebRtc: Boolean, val blockTrackers: Boolean)

    /** Restarts the tunnel on [newProxy] with the given settings. Completes with true when it is up. */
    suspend fun reconnect(newProxy: ProxyItem, a: Args): Boolean {
        val sessionId = newSession()
        stopHealthMonitor()
        if (lastArgs?.dns != a.dns) preferredDns = null // the user picked another DNS
        lastArgs = ConnectArgs(newProxy, a.bypassTorrents, a.bypassDomesticRu, a.dns, a.blockQuic, a.blockWebRtc, a.blockTrackers)
        // Armed before the old tunnel is torn down, so the switch-over never runs in the clear
        armKillSwitchIfEnabled()
        setSessionState(ConnectionState.Reconnecting, sessionId)
        val job = scope.launch { runSession(newProxy, sessionId, stopFirst = true) }
        synchronized(sessionSync) { activeSessionJob = job }
        job.join()
        return state == ConnectionState.Connected && activeProxy === newProxy && currentSessionId == sessionId
    }

    private suspend fun runSession(proxy: ProxyItem, sessionId: String, stopFirst: Boolean = false) {
        try {
            processLock.withLock {
                if (sessionId != currentSessionId || !coroutineContext.isActive) return
                if (stopFirst) {
                    stopTraffic()
                    tun.stop(keepBlocked = killSwitchEnabled)
                }
                setSessionState(ConnectionState.Validating, sessionId)
                val (success, error) = startTunnel(proxy, sessionId)

                if (sessionId != currentSessionId || !coroutineContext.isActive) {
                    if (success) tun.stop(keepBlocked = killSwitchEnabled)
                    return
                }
                if (success) {
                    activeProxy = proxy
                    connectedStartTime = System.currentTimeMillis()
                    startTraffic()
                    setSessionState(ConnectionState.Connected, sessionId)
                    startHealthMonitor(sessionId)
                } else {
                    tun.stop(keepBlocked = killSwitchEnabled)
                    if (!killSwitchEnabled) ZionVpnService.instance?.shutdown()
                    activeProxy = null
                    connectedStartTime = null
                    setSessionState(ConnectionState.Failed, sessionId, error)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tun.stop(keepBlocked = killSwitchEnabled)
            activeProxy = null
            connectedStartTime = null
            setSessionState(ConnectionState.Failed, sessionId, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Starts the tunnel with the current settings. The address the last check found working goes first;
     * if the server still passes no traffic, all of its addresses are checked right away and the start is
     * repeated on one that works.
     */
    private suspend fun startTunnel(proxy: ProxyItem, sessionId: String): Pair<Boolean, String> {
        val a = lastArgs!!
        val settings = TunnelSettings(a.bypassTorrents, a.bypassDomesticRu, a.dns, null, a.blockQuic, a.blockWebRtc, a.blockTrackers, directApps, directSites)
        suspend fun start(address: String?) = tun.start(proxy, settings, preferredDns, address, killSwitchEnabled)

        val first = start(proxy.workingAddress)
        if (first.success || !tun.lastStartServerUnreachable || !coroutineContext.isActive || sessionId != currentSessionId || isIpLiteral(proxy.cleanHost)) {
            return first.success to first.error
        }

        onLog("«${proxy.cleanName}» не ответил. Проверяем все его адреса…")
        val check = ServerChecker(app).check(listOf(proxy)).firstOrNull()
        if (check == null || !coroutineContext.isActive || sessionId != currentSessionId) return first.success to first.error

        if (check.outcome == ServerCheckOutcome.Working && check.address != null) {
            proxy.workingAddress = check.address
            onLog("Рабочий адрес нашёлся (${check.delayMs} ms), подключаемся через него.")
            val second = start(check.address)
            return second.success to second.error
        }
        if (check.outcome == ServerCheckOutcome.NotWorking) {
            proxy.workingAddress = null
            proxy.status = ProxyStatus.Offline
            proxy.pingMs = -1
            onLog("«${proxy.cleanName}» не отвечает ни по одному адресу.")
        }
        return first.success to first.error
    }

    /** The user asked to disconnect: direct access is what they want now (Kill Switch is released too). */
    fun disconnect(): Job {
        synchronized(sessionSync) {
            activeSessionJob?.cancel()
            activeSessionJob = null
            currentSessionId = ""
        }
        stopHealthMonitor()
        preferredDns = null
        resetState(ConnectionState.Stopping)
        return scope.launch {
            try {
                processLock.withLock {
                    stopTraffic()
                    tun.stop(keepBlocked = false)
                    activeProxy = null
                    connectedStartTime = null
                    ZionVpnService.instance?.shutdown()
                    resetState(ConnectionState.Disconnected)
                }
            } catch (_: Exception) {
                ZionVpnService.instance?.shutdown()
                resetState(ConnectionState.Disconnected)
            }
        }
    }

    /** Android took the VPN away (another VPN app, or the user switched Zion off in the system settings). */
    fun onRevoked() {
        val sessionId = synchronized(sessionSync) { currentSessionId }
        if (sessionId.isEmpty()) return
        stopHealthMonitor()
        scope.launch {
            stopTraffic()
            tun.stop(keepBlocked = false)
            activeProxy = null
            connectedStartTime = null
            setSessionState(ConnectionState.Failed, sessionId, "Android отключил VPN Zion: его заняло другое приложение или VPN выключен в настройках.")
        }
    }

    // ------------------------------------------------------------------ traffic

    private val counter = ProxiedTrafficCounter("proxy-out")

    private fun startTraffic() {
        stopTraffic()
        val endpoint = tun.control ?: return
        val api = ClashApi(endpoint)
        counter.reset()
        lastDownloadAt = System.currentTimeMillis()
        trafficJob = scope.launch {
            launch {
                api.streamTraffic { up, down ->
                    if (down > 0) lastDownloadAt = System.currentTimeMillis()
                    if (state == ConnectionState.Connected && trafficVisible) onTraffic(up, down)
                }
            }
            launch {
                while (isActive) {
                    if (trafficVisible) {
                        api.connections()?.let { if (counter.apply(it) && state == ConnectionState.Connected) onProxiedTotal(counter.proxiedBytes) }
                    }
                    delay(1000)
                }
            }
        }
    }

    private fun stopTraffic() {
        trafficJob?.cancel()
        trafficJob = null
    }

    // ------------------------------------------------------------------ health

    private fun startHealthMonitor(sessionId: String) {
        stopHealthMonitor()
        isLinkHealthy = true
        isInternetDown = false
        lastDownloadAt = System.currentTimeMillis()
        healthJob = scope.launch { healthLoop(sessionId) }
    }

    private fun stopHealthMonitor() {
        healthJob?.cancel()
        healthJob = null
    }

    private suspend fun healthLoop(sessionId: String) {
        var failures = 0
        var firstRound = true
        var dnsQuietUntil = 0L
        try {
            while (coroutineContext.isActive) {
                // The first round comes quickly: a DNS server that does not work through the VPN should be
                // replaced before the user notices that sites do not open.
                delay(if (firstRound) FIRST_DNS_CHECK_DELAY else HEALTH_INTERVAL)
                if (sessionId != currentSessionId || state != ConnectionState.Connected) return

                // Data is flowing in, so the link is obviously alive: no probe needed
                val sinceDownload = System.currentTimeMillis() - lastDownloadAt
                val alive = firstRound || sinceDownload < HEALTH_INTERVAL || probeLink()
                firstRound = false
                if (sessionId != currentSessionId) return

                if (alive) {
                    failures = 0
                    setLinkHealth(true, internetDown = false)
                    // The link probe needs no DNS, so a dead DNS server would otherwise go unnoticed
                    if (System.currentTimeMillis() >= dnsQuietUntil && !isDnsWorking()) {
                        if (sessionId != currentSessionId) return
                        if (tryDnsFailover(sessionId)) return // the tunnel restarts; a new monitor takes over
                        dnsQuietUntil = System.currentTimeMillis() + DNS_QUIET_AFTER_NO_ALTERNATIVE
                    }
                } else if (++failures >= HEALTH_FAILURES_BEFORE_DOWN) {
                    // Do not blame the server when the whole network is gone: another server would not help
                    val internet = InternetProbe.isInternetReachable(2500)
                    if (sessionId != currentSessionId) return
                    setLinkHealth(false, internetDown = internet == false)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onLog("Проверка соединения остановлена: ${e.message}")
        }
    }

    private suspend fun probeLink(): Boolean {
        val endpoint = tun.control ?: return true
        val api = ClashApi(endpoint)
        for (url in HEALTH_PROBE_URLS) {
            val r = api.delay("proxy-out", url, 4000)
            if (r.success && r.delayMs > 0) return true
        }
        return false
    }

    /** Asks the tunnel's DNS server a real question through the VPN; one quick retry before calling it dead. */
    private suspend fun isDnsWorking(): Boolean {
        val target = tun.activeDnsTarget
        if (target == null || target.host.isEmpty()) return true
        if (DohProbe.probe(target) != null) return true
        delay(DNS_RECHECK_DELAY)
        return DohProbe.probe(target) != null
    }

    /**
     * Finds a DNS server that answers through the VPN and restarts the tunnel with it. Auto mode may pick
     * any provider; a provider chosen by the user only falls back to its own backup address.
     */
    private suspend fun tryDnsFailover(sessionId: String): Boolean {
        val current = tun.activeDnsTarget ?: return false
        val args = lastArgs ?: return false
        val candidates = DnsConstants.failoverCandidates(args.dns, current)
        onLog("DNS ${current.name} (${current.ip}) не отвечает через VPN. Ищем замену…")
        val ranked = DohProbe.rank(candidates)
        if (sessionId != currentSessionId) return false

        if (ranked.isEmpty()) {
            if (args.dns == DnsProvider.Auto) onLog("Ни один DNS-сервер не ответил через VPN. Повторим через минуту.")
            else {
                onLog("DNS ${current.name} не отвечает через VPN, запасного адреса тоже нет.")
                onDnsUnavailable(current.name)
            }
            return false
        }
        val (best, ms) = ranked[0]
        preferredDns = best
        onLog("DNS переключён: ${current.name} (${current.ip}) → ${best.name} (${best.ip}, $ms ms).")
        onDnsSwitched(current.name, best.name)
        val a = Args(args.bypassTorrents, args.bypassDomesticRu, args.dns, args.blockQuic, args.blockWebRtc, args.blockTrackers)
        scope.launch { reconnectKeepingDns(args.proxy, a) }
        return true
    }

    /** Like [reconnect] but keeps the DNS server just found (the user did not change the DNS choice). */
    private suspend fun reconnectKeepingDns(proxy: ProxyItem, a: Args) {
        val keep = preferredDns
        reconnect(proxy, a)
        if (keep != null && preferredDns == null) preferredDns = keep
    }

    private fun setLinkHealth(healthy: Boolean, internetDown: Boolean) {
        if (isLinkHealthy == healthy && isInternetDown == internetDown) return
        isLinkHealthy = healthy
        isInternetDown = internetDown
        onLog(when {
            healthy -> "Связь с сервером восстановлена."
            internetDown -> "Нет интернета: ждём, пока сеть вернётся. Сервер не меняем."
            else -> "Сервер не отвечает на контрольные запросы, хотя интернет есть."
        })
        onLinkHealthChanged(healthy)
    }

    companion object {
        private const val HEALTH_INTERVAL = 15_000L
        private const val HEALTH_FAILURES_BEFORE_DOWN = 2
        private val HEALTH_PROBE_URLS = listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")
        private const val FIRST_DNS_CHECK_DELAY = 3_000L
        private const val DNS_RECHECK_DELAY = 3_000L
        private const val DNS_QUIET_AFTER_NO_ALTERNATIVE = 60_000L
    }
}
