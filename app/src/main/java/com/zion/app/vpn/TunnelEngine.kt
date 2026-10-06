package com.zion.app.vpn

import android.content.Context
import com.zion.app.core.ClashApi
import com.zion.app.core.ClashDelayResult
import com.zion.app.core.ConfigBuilder
import com.zion.app.core.ControlEndpoint
import com.zion.app.core.DnsBenchmarkResult
import com.zion.app.core.DnsConstants
import com.zion.app.core.DohTarget
import com.zion.app.core.TunnelSettings
import com.zion.app.model.DnsProvider
import com.zion.app.model.ProxyItem
import com.zion.app.model.ProxyStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

/**
 * Starts and stops the tunnel (the Windows TunRoutingEngine). The core runs inside the app; its control API
 * listens on a free loopback port with a fresh secret for every start.
 */
class TunnelEngine(private val context: Context) {
    private val lock = Mutex()
    private var box: Box? = null
    private val coreLog = CoreLog(java.io.File(context.cacheDir, "core/tunnel.log"))
    private val logScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var logJob: kotlinx.coroutines.Job? = null

    var isActive = false
        private set

    /** The last start failed because the server passed no traffic (the core itself came up fine). */
    var lastStartServerUnreachable = false
        private set

    /** This session's control API (null while no tunnel is up). */
    @Volatile var control: ControlEndpoint? = null
        private set

    /** Name of the DNS server the running tunnel uses (e.g. "Cloudflare"). */
    @Volatile var activeDnsName = ""
        private set

    /** The DNS server the running tunnel sends its questions to first. */
    @Volatile var activeDnsTarget: DohTarget? = null
        private set

    var onLog: (String) -> Unit = {}

    class StartResult(val success: Boolean, val error: String = "")

    /**
     * Brings the tunnel up through [proxy] and proves with a real request that traffic passes.
     * [keepBlocked] = Kill Switch: whatever happens, the internet never runs in the clear.
     */
    suspend fun start(
        proxy: ProxyItem, settings: TunnelSettings, preferredDns: DohTarget?, serverAddress: String?, keepBlocked: Boolean,
    ): StartResult = lock.withLock {
        stopInternal(keepBlocked)
        lastStartServerUnreachable = false
        if (NetworkMonitor.underlying == null) {
            return@withLock fail("Нет подключения к интернету: включите Wi-Fi или мобильные данные.", keepBlocked)
        }
        Box.setup(context)
        ZionVpnService.ensureRunning(context)

        // 1. Which DNS server goes first
        var dnsProvider = settings.dnsProvider
        var autoOrder: List<DnsBenchmarkResult>? = null
        if (preferredDns != null) {
            // A server already proven to answer through the VPN (DNS failover): use it as is
            autoOrder = listOf(DnsBenchmarkResult(preferredDns.name, preferredDns.ip, preferredDns.path, 0, true))
            dnsProvider = DnsProvider.Auto
            onLog("DNS: используем ${preferredDns.name} (${preferredDns.ip}), он отвечает через VPN.")
        } else if (dnsProvider == DnsProvider.Auto) {
            autoOrder = measureDnsLatency()
            autoOrder.firstOrNull { it.success }?.let { onLog("⚡ DNS Auto: Выбран самый быстрый DoH сервер — ${it.name} (${it.latencyMs} ms)") }
        }
        val effective = settings.copy(dnsProvider = dnsProvider, autoDnsOrder = autoOrder)
        activeDnsTarget = DnsConstants.resolvePrimary(dnsProvider, autoOrder)
        activeDnsName = activeDnsTarget?.name ?: ""

        if (settings.directApps.isNotEmpty()) onLog("Мимо VPN идут приложения: ${settings.directApps.joinToString(", ")}")

        // 2. Start the core. Its control port is picked free for every session; should another program take it
        //    in the instant before the core binds it, the start is repeated on another port.
        var error = ""
        for (attempt in 1..2) {
            val endpoint = ControlEndpoint(Box.freeLoopbackPort(), Box.randomSecret(16))
            coreLog.reset()
            val config = ConfigBuilder.generateTunnelConfig(proxy, effective, endpoint, serverAddress, coreLog.file.path, java.io.File(context.filesDir, "core/tunnel.db").path).toString()

            Box.check(config)?.let { return@withLock fail("Ошибка валидации sing-box: $it", keepBlocked) }

            val b = Box(context, tunnel = true)
            val started = withContext(Dispatchers.IO) { runCatching { b.start(config) } }
            if (started.isFailure) {
                b.close()
                error = "sing-box завершил работу: ${started.exceptionOrNull()?.message ?: "неизвестная ошибка"}"
                if (attempt == 1 && ConfigBuilder.isPortConflict(error)) {
                    onLog("Локальный порт оказался занят другой программой. Запускаем ядро на других портах.")
                    continue
                }
                return@withLock fail(error, keepBlocked)
            }
            box = b
            control = endpoint

            val api = ClashApi(endpoint)
            var alive = false
            for (d in longArrayOf(50, 100, 150, 250, 350, 500, 500, 750, 1000)) {
                delay(d)
                if (api.isAlive(400)) { alive = true; break }
            }
            if (!alive) return@withLock fail("Ядро sing-box не отвечает на управляющие запросы: ${api.lastError}", keepBlocked)

            // 3. Authoritative check through the control API (several test pages)
            var outboundDelay = -1
            var last: ClashDelayResult? = null
            for (url in TEST_URLS) {
                if (!coroutineContext.isActive) break
                val r = api.delay("proxy-out", url, 3000)
                last = r
                if (r.success && r.delayMs > 0) { outboundDelay = r.delayMs; break }
                onLog("⚠️ Тест через $url не прошёл: ${r.errorMessage}. Пробуем альтернативный эндпоинт...")
            }
            if (outboundDelay < 0) {
                lastStartServerUnreachable = coroutineContext.isActive
                var msg = "Туннель запущен, но прокси-сервер не отвечает на контрольные сетевые запросы."
                if (last != null) {
                    msg = when {
                        last.statusCode == 407 || last.errorMessage.contains("407") || last.errorMessage.contains("auth", true) ->
                            "Ошибка аутентификации прокси-сервера (проверьте логин/пароль или UUID)."
                        last.statusCode == 400 || last.statusCode == 404 -> "Сбой Clash API: ${last.errorMessage} (HTTP ${last.statusCode})"
                        last.errorMessage.isNotBlank() -> "Сбой проверки соединения: ${last.errorMessage}"
                        else -> msg
                    }
                }
                return@withLock fail(msg, keepBlocked)
            }

            // The server just proved it works: that is the freshest check there is
            proxy.status = ProxyStatus.Online
            proxy.pingMs = outboundDelay
            isActive = true
            onLog("⚡ VPN-туннель активен! ($outboundDelay ms) Весь трафик → ${proxy.displayAddress}")
            forwardCoreLog()
            // While the tunnel runs, the core's warnings and errors go to the event log as they appear
            logJob = logScope.launch {
                while (true) {
                    delay(3000)
                    forwardCoreLog()
                }
            }
            return@withLock StartResult(true)
        }
        fail(error, keepBlocked)
    }

    private fun fail(error: String, keepBlocked: Boolean): StartResult {
        forwardCoreLog()
        val problem = coreLog.lastProblem()
        stopInternal(keepBlocked)
        return StartResult(false, if (problem != null && !error.contains(problem)) "$error\nЯдро: $problem" else error)
    }

    private fun forwardCoreLog() {
        coreLog.newLines().forEach { onLog("sing-box: $it") }
    }

    suspend fun stop(keepBlocked: Boolean) = lock.withLock { stopInternal(keepBlocked) }

    private fun stopInternal(keepBlocked: Boolean) {
        val service = ZionVpnService.instance
        // Kill Switch: the blocking interface takes over before the tunnel goes, so nothing runs in the clear
        if (keepBlocked) service?.holdBlocker()
        logJob?.cancel()
        logJob = null
        val b = box
        box = null
        val wasActive = isActive || b != null
        isActive = false
        control = null
        b?.close()
        service?.closeTun()
        if (!keepBlocked) service?.releaseBlocker()
        if (wasActive) onLog(if (keepBlocked) "Туннель остановлен. Kill Switch держит интернет заблокированным." else "Туннель остановлен. Прямой интернет восстановлен.")
    }

    companion object {
        val TEST_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "https://www.google.com/generate_204",
        )

        /** TCP to every DoH server's port 443 (450 ms cap), fastest first. Goes out through the real network. */
        suspend fun measureDnsLatency(): List<DnsBenchmarkResult> = coroutineScope {
            DnsConstants.allReal().map { e ->
                async(Dispatchers.IO) {
                    val start = System.nanoTime()
                    try {
                        Socket().use { s ->
                            NetworkMonitor.underlying?.bindSocket(s)
                            s.tcpNoDelay = true
                            s.connect(InetSocketAddress(e.primaryIp, 443), 450)
                        }
                        DnsBenchmarkResult(e.displayName, e.primaryIp, e.dohPath, ((System.nanoTime() - start) / 1_000_000).toInt(), true)
                    } catch (_: Exception) {
                        DnsBenchmarkResult(e.displayName, e.primaryIp, e.dohPath, 9999, false)
                    }
                }
            }.awaitAll().sortedBy { it.latencyMs }
        }
    }
}
