package com.zion.app.vpn

import android.content.Context
import com.zion.app.core.ClashApi
import com.zion.app.core.ConfigBuilder
import com.zion.app.core.ControlEndpoint
import com.zion.app.core.isIpLiteral
import com.zion.app.model.ProxyItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

enum class ServerCheckOutcome {
    /** A real page loaded through the server. */
    Working,

    /** The server passed no traffic on any of its addresses, or the core refused its settings. */
    NotWorking,

    /** The check itself could not run; nothing new is known about the server. */
    Unknown,
}

/** [address] = the fastest working address when the server is a name (null for IP servers). */
class ServerCheckResult(val server: ProxyItem, val outcome: ServerCheckOutcome, val delayMs: Int, val address: String?, val detail: String)

/**
 * The real server check (same as Windows). A separate, short-lived copy of the core loads a test page
 * through every server: the same test the tunnel passes before it reports "connected". Each address of a
 * server name is checked on its own; the server works if any address does, and the fastest one is
 * remembered so the tunnel can connect straight to it. The copy's sockets leave through the real network
 * (protect), so it measures the servers themselves and not the way through the current VPN server.
 */
class ServerChecker(private val context: Context) {

    companion object {
        val TEST_URLS = listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")
        private const val TEST_TIMEOUT_MS = 5000
        private const val PARALLELISM = 16
        private const val MAX_ADDRESSES_PER_SERVER = 8
        private const val START_TIMEOUT_MS = 5000L
    }

    private class Target(val server: ProxyItem, val address: String?)

    private val coreLog = CoreLog(java.io.File(context.cacheDir, "core/check.log"))

    /** Why the name lookup could not run (shown with the servers it left unknown). */
    private var resolveError = ""

    /** Checks the servers (provider dividers are skipped) and reports each one as soon as it is known. Never throws. */
    suspend fun check(servers: List<ProxyItem>, onResult: (ServerCheckResult) -> Unit = {}): List<ServerCheckResult> {
        val pending = servers.filter { !it.isDivider }.distinct()
        val results = mutableListOf<ServerCheckResult>()
        val report: (ServerCheckResult) -> Unit = { r ->
            synchronized(results) { results += r }
            runCatching { onResult(r) }
        }
        if (pending.isEmpty()) return results
        Box.setup(context)

        // 1. Every address of every server name, looked up by the core itself through the real network
        val names = pending.map { it.cleanHost }.filter { !isIpLiteral(it) }.distinctBy { it.lowercase() }
        val lookups = if (names.isNotEmpty()) resolve(names) else emptyMap()
        if (!coroutineContext.isActive) return results

        val targets = mutableListOf<Target>()
        for (server in pending) {
            if (isIpLiteral(server.cleanHost)) {
                targets += Target(server, null)
                continue
            }
            val (status, addresses) = lookups[server.cleanHost.lowercase()] ?: (-1 to emptyList())
            when {
                addresses.isNotEmpty() -> addresses.take(MAX_ADDRESSES_PER_SERVER).forEach { targets += Target(server, it) }
                status == 3 -> report(ServerCheckResult(server, ServerCheckOutcome.NotWorking, -1, null, "Адрес сервера не существует"))
                status == 0 -> report(ServerCheckResult(server, ServerCheckOutcome.NotWorking, -1, null, "У адреса сервера нет IPv4-адресов"))
                else -> report(ServerCheckResult(server, ServerCheckOutcome.Unknown, -1, null, if (resolveError.isNotEmpty()) "Не удалось узнать адрес сервера: $resolveError" else "Не удалось узнать адрес сервера"))
            }
        }

        // 2. A real page through every address
        measure(targets, report)
        return results
    }

    /** One running copy of the core; its cache file is its own and goes away with it. */
    private class Prober(val box: Box, val api: ClashApi, val cache: java.io.File) {
        fun close() {
            box.close()
            cache.delete()
        }
    }

    private class StartResult(val prober: Prober?, val error: String, val badIndex: Int?, val portConflict: Boolean)

    private suspend fun startProber(targets: List<Target>): StartResult {
        val endpoint = ControlEndpoint(Box.freeLoopbackPort(), Box.randomSecret(16))
        coreLog.reset()
        val cache = java.io.File(context.cacheDir, "core/check-${Box.randomSecret(6)}.db")
        val config = ConfigBuilder.generateCheckConfig(targets.map { it.server to it.address }, endpoint, coreLog.file.path, cache.path).toString()
        val box = Box(context, tunnel = false)
        val started = withContext(Dispatchers.IO) { runCatching { box.start(config) } }
        if (started.isFailure) {
            box.close()
            cache.delete()
            val err = started.exceptionOrNull()?.message ?: coreLog.lastProblem() ?: "Ядро не запустилось"
            return StartResult(null, err, ConfigBuilder.parseBadOutbound(err), ConfigBuilder.isPortConflict(err))
        }
        val api = ClashApi(endpoint)
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && coroutineContext.isActive) {
            if (api.isAlive(300)) return StartResult(Prober(box, api, cache), "", null, false)
            delay(100)
        }
        box.close()
        cache.delete()
        val why = coreLog.lastProblem()?.let { "; ядро: $it" } ?: ""
        return StartResult(null, if (coroutineContext.isActive) "Ядро не отвечает на управляющие запросы: ${api.lastError}$why" else "Проверка отменена", null, false)
    }

    private suspend fun resolve(names: List<String>): Map<String, Pair<Int, List<String>>> {
        val lookups = ConcurrentHashMap<String, Pair<Int, List<String>>>()
        val start = startProber(emptyList())
        resolveError = start.error
        val prober = start.prober ?: return lookups
        try {
            val gate = Semaphore(PARALLELISM)
            coroutineScope {
                for (name in names) launch {
                    gate.withPermit {
                        // Two questions: some name servers hand out only part of a pool per answer
                        val first = prober.api.queryDns(name)
                        val second = if (first.status == 0) prober.api.queryDns(name) else first
                        val all = (first.addresses + second.addresses).distinct().map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }
                        lookups[name.lowercase()] = (if (first.status == 0 || second.status == 0) 0 else first.status) to all
                    }
                }
            }
        } finally {
            withContext(Dispatchers.IO) { prober.close() }
        }
        return lookups
    }

    private suspend fun measure(initial: List<Target>, report: (ServerCheckResult) -> Unit) {
        var targets = initial
        var portRetries = 0
        val job = coroutineContext[kotlinx.coroutines.Job]
        while (targets.isNotEmpty() && coroutineContext.isActive) {
            val start = startProber(targets)
            val prober = start.prober
            if (prober == null) {
                // A server the core refuses to load would stop the whole check: report it and go on without it
                val bad = start.badIndex
                if (bad != null && bad in targets.indices) {
                    val server = targets[bad].server
                    report(ServerCheckResult(server, ServerCheckOutcome.NotWorking, -1, null, "Ядро не принимает настройки сервера: ${start.error}"))
                    targets = targets.filter { it.server !== server }
                    continue
                }
                if (start.portConflict && ++portRetries <= 2) continue
                if (coroutineContext.isActive) {
                    targets.map { it.server }.distinct().forEach { report(ServerCheckResult(it, ServerCheckOutcome.Unknown, -1, null, start.error)) }
                }
                return
            }

            try {
                val delays = ConcurrentHashMap<Int, Int>()
                val errors = ConcurrentHashMap<Int, String>()
                val groups = LinkedHashMap<ProxyItem, MutableList<Int>>()
                targets.forEachIndexed { i, t -> groups.getOrPut(t.server) { mutableListOf() } += i }

                fun verdict(server: ProxyItem, cancelled: Boolean): ServerCheckResult {
                    val group = groups.getValue(server)
                    val best = group.filter { delays.containsKey(it) }.minByOrNull { delays.getValue(it) }
                    if (best != null) return ServerCheckResult(server, ServerCheckOutcome.Working, delays.getValue(best), targets[best].address, "")
                    val detail = group.map { errors[it] ?: "" }.firstOrNull { it.isNotEmpty() } ?: ""
                    return ServerCheckResult(server, if (cancelled) ServerCheckOutcome.Unknown else ServerCheckOutcome.NotWorking, -1, null, detail)
                }

                // Measures the servers' addresses with one test page; a server is finished the moment all its addresses are in
                suspend fun run(servers: Collection<ProxyItem>, url: String, onDone: (ProxyItem) -> Unit) {
                    val remaining = ConcurrentHashMap<ProxyItem, AtomicInteger>()
                    servers.forEach { remaining[it] = AtomicInteger(groups.getValue(it).size) }
                    val gate = Semaphore(PARALLELISM)
                    coroutineScope {
                        for (server in servers) for (i in groups.getValue(server)) launch {
                            gate.withPermit {
                                val r = prober.api.delay(ConfigBuilder.checkTag(i), url, TEST_TIMEOUT_MS)
                                if (r.success && r.delayMs > 0) delays[i] = r.delayMs else errors[i] = r.errorMessage
                                if (remaining.getValue(server).decrementAndGet() == 0) onDone(server)
                            }
                        }
                    }
                }

                // Pass 1: the main page through every address; a server with a working address is done.
                // Pass 2: servers with no working address get a second chance with another page.
                val silent = java.util.concurrent.ConcurrentLinkedQueue<ProxyItem>()
                run(groups.keys, TEST_URLS[0]) { server ->
                    if (groups.getValue(server).any { delays.containsKey(it) }) report(verdict(server, false)) else silent += server
                }
                if (!coroutineContext.isActive) return
                if (silent.isNotEmpty()) run(silent.toList(), TEST_URLS[1]) { server -> report(verdict(server, job?.isActive == false)) }
            } finally {
                withContext(Dispatchers.IO) { prober.close() }
            }
            return
        }
    }
}
