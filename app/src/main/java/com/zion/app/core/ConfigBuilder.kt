package com.zion.app.core

import com.zion.app.model.DnsProvider
import com.zion.app.model.ProxyItem
import com.zion.app.model.ProxyProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The control API of one running core: a free loopback port and a random secret, fresh for every start. */
data class ControlEndpoint(val port: Int, val secret: String) {
    override fun toString() = "control 127.0.0.1:$port"
}

/** Everything the tunnel config depends on. */
data class TunnelSettings(
    val bypassTorrents: Boolean = false,
    val bypassDomesticRu: Boolean = false,
    val dnsProvider: DnsProvider = DnsProvider.Auto,
    val autoDnsOrder: List<DnsBenchmarkResult>? = null,
    val blockQuic: Boolean = false,
    val blockWebRtc: Boolean = false,
    val blockTrackers: Boolean = false,
    /** Package names that never enter the VPN. */
    val directApps: List<String> = emptyList(),
    val directSites: List<String> = emptyList(),
)

/** sing-box configs: the tunnel (same rules as Windows) and the short-lived server check. */
object ConfigBuilder {

    /** Torrent clients for Android: with "Обход торрентов" they never enter the VPN (like the process rule on Windows). */
    val TORRENT_APPS = listOf(
        "com.utorrent.client", "com.utorrent.client.pro", "com.bittorrent.client", "com.bittorrent.client.pro",
        "org.proninyaroslav.libretorrent", "com.delphicoder.flud", "com.delphicoder.flud.paid",
        "hu.tagsoft.ttorrent.lite", "hu.tagsoft.ttorrent.pro", "com.frostwire.android", "com.biglybt.android.client",
        "co.we.torrent", "com.vuze.torrent.downloader", "intelligems.torrdroid", "com.mobilityflow.torrent",
    )

    val TRACKER_DOMAINS = listOf(
        "google-analytics.com", "analytics.google.com", "googletagmanager.com", "doubleclick.net", "adservice.google.com",
        "facebook.net", "connect.facebook.net", "mc.yandex.ru", "top-fwz1.mail.ru", "app-measurement.com",
        "crashlytics.com", "adjust.com", "appsflyer.com",
    )

    val DOMESTIC_DOMAINS = listOf(
        ".ru", "ru", ".xn--p1ai", "xn--p1ai", ".рф", "рф", ".su", "su",
        "osnova.io", "soccer365.me", "s365.me", "soccer365.ru",
        "pep.link", "pepper.com", "pepper.ru",
        "habr.com", "habrastorage.org", "4pda.to", "4pda.ws",
        "championat.com", "sports.ru", "s5o.ru",
        "userapi.com", "vk.me", "vkuser.net", "vks.im", "vk.com", "vk.ru",
        "yastatic.net", "yastat.net", "yandex.ru", "yandex.net", "ya.ru", "dzen.ru",
        "kinopoisk.ru", "kinopoisk.com",
        "ozon.ru", "ozonusercontent.com", "wildberries.ru", "wb.ru", "wbstatic.net",
        "avito.ru", "avito.st", "pikabu.ru", "rutube.ru", "rutubelist.ru",
        "mail.ru", "rambler.ru", "2gis.ru", "2gis.com", "rzd.ru",
        "gosuslugi.ru", "nalog.gov.ru",
        "sberbank.ru", "sber.ru", "tbank.ru", "tinkoff.ru", "vtb.ru", "alfabank.ru",
        "auto.ru", "cian.ru", "domclick.ru", "megamarket.ru", "dns-shop.ru", "citilink.ru",
    )

    private fun strings(list: Iterable<String>) = JsonArray(list.map { JsonPrimitive(it) })

    /**
     * Clean list of package names for the VPN exclusion: no blanks, no duplicates, never Zion itself
     * (excluding the app that runs the tunnel would make no sense).
     */
    fun sanitizeDirectApps(names: Iterable<String>?, selfPackage: String): List<String> {
        val result = mutableListOf<String>()
        names ?: return result
        val pkg = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        for (raw in names) {
            val n = raw.trim()
            if (n.isEmpty() || !pkg.matches(n) || n == selfPackage) continue
            if (result.any { it.equals(n, true) }) continue
            result += n
        }
        return result
    }

    fun generateTunnelConfig(proxy: ProxyItem, s: TunnelSettings, control: ControlEndpoint, serverAddress: String? = null, logFile: String? = null, cacheFile: String? = null): JsonObject {
        val host = proxy.cleanHost
        val routeRules = mutableListOf<JsonObject>(
            buildJsonObject { put("action", "sniff") },
            buildJsonObject { put("protocol", "dns"); put("action", "hijack-dns") },
            buildJsonObject { put("ip_is_private", true); put("outbound", "direct-out") },
            // IPv6 leak protection: reject any IPv6 traffic inside the tunnel
            buildJsonObject { put("ip_version", 6); put("action", "reject") },
        )
        val dnsRules = mutableListOf<JsonObject>(
            // Reject ECH DNS queries (HTTPS RR 65 & SVCB RR 64): they make TSPU reset Cloudflare connections
            buildJsonObject {
                put("query_type", strings(listOf("HTTPS", "SVCB")))
                put("action", "reject")
                put("method", "default")
            },
        )

        // 1. Torrents: the protocol by DPI here; the clients themselves are kept out of the VPN (exclude_package)
        if (s.bypassTorrents) {
            routeRules += buildJsonObject { put("protocol", strings(listOf("bittorrent"))); put("outbound", "direct-out") }
        }

        // 1a. Sites the user sends around the VPN (with subdomains). Ahead of every other rule, so
        //     "Обход РФ", tracker blocking and the QUIC block never override the user's own choice.
        val sites = DirectSites.sanitize(s.directSites)
        if (sites.isNotEmpty()) {
            routeRules += buildJsonObject { put("domain_suffix", strings(sites)); put("outbound", "direct-out") }
            dnsRules += buildJsonObject { put("domain_suffix", strings(sites)); put("server", "direct-dns") }
        }

        // Trackers are blocked before the RU bypass: otherwise mc.yandex.ru would match the .ru rule first
        if (s.blockTrackers) {
            routeRules += buildJsonObject { put("domain_suffix", strings(TRACKER_DOMAINS)); put("action", "reject") }
            dnsRules += buildJsonObject { put("domain_suffix", strings(TRACKER_DOMAINS)); put("action", "reject") }
        }

        // 2. Domestic domains go out directly (and are resolved by the local resolver)
        if (s.bypassDomesticRu) {
            routeRules += buildJsonObject { put("domain_suffix", strings(DOMESTIC_DOMAINS)); put("outbound", "direct-out") }
            dnsRules += buildJsonObject { put("domain_suffix", strings(DOMESTIC_DOMAINS)); put("server", "direct-dns") }
        }

        // 3. Reject QUIC (UDP 443/80) and WebRTC STUN for tunnelled traffic
        if (s.blockQuic) {
            routeRules += buildJsonObject { put("network", "udp"); putJsonArray("port") { add(JsonPrimitive(443)); add(JsonPrimitive(80)) }; put("action", "reject") }
        }
        if (s.blockWebRtc) {
            routeRules += buildJsonObject {
                put("network", "udp")
                putJsonArray("port") { add(JsonPrimitive(19302)); add(JsonPrimitive(3478)); add(JsonPrimitive(5349)) }
                put("action", "reject")
            }
        }

        // 4. The server itself goes out directly (no DNS loops)
        if (!serverAddress.isNullOrBlank() && isIpLiteral(serverAddress)) {
            routeRules += buildJsonObject { put("ip_cidr", strings(listOf("$serverAddress/32"))); put("outbound", "direct-out") }
        }
        if (host.isNotEmpty()) {
            if (isIpLiteral(host)) {
                routeRules += buildJsonObject { put("ip_cidr", strings(listOf(if (host.contains('/')) host else "$host/32"))); put("outbound", "direct-out") }
            } else {
                dnsRules += buildJsonObject { put("domain", strings(listOf(host))); put("server", "direct-dns") }
                routeRules += buildJsonObject { put("domain", strings(listOf(host))); put("outbound", "direct-out") }
            }
        }

        // 5. DNS servers for the chosen provider
        val dnsServers = mutableListOf<JsonObject>()
        fun doh(tag: String, ip: String, path: String, tlsHost: String?) = buildJsonObject {
            put("tag", tag)
            put("type", "https")
            put("server", ip)
            put("path", path)
            if (tlsHost != null) putJsonObject("tls") { put("enabled", true); put("server_name", tlsHost) }
            put("detour", "proxy-out")
        }
        if (s.dnsProvider == DnsProvider.Auto) {
            val order = s.autoDnsOrder
            if (!order.isNullOrEmpty()) {
                order.forEachIndexed { idx, dns ->
                    val match = DnsConstants.findByIp(dns.serverIp)
                    val tag = if (idx == 0) "remote-dns" else (match?.singBoxTag ?: "remote-dns-${dns.name.lowercase().replace(" ", "")}")
                    dnsServers += doh(tag, dns.serverIp, dns.path, match?.dohHost)
                }
            } else {
                for (e in DnsConstants.allReal()) {
                    dnsServers += doh(if (e.provider == DnsProvider.Cloudflare) "remote-dns" else e.singBoxTag, e.primaryIp, e.dohPath, e.dohHost)
                }
            }
        } else {
            val e = DnsConstants.entry(s.dnsProvider)
            dnsServers += doh("remote-dns", e.primaryIp, e.dohPath, e.dohHost)
            if (e.secondaryIp.isNotBlank()) dnsServers += doh("remote-dns-backup", e.secondaryIp, e.dohPath, e.dohHost)
        }
        dnsServers += buildJsonObject { put("tag", "direct-dns"); put("type", "local") }

        // Apps outside the VPN: the user's choice, plus the torrent clients with "Обход торрентов"
        val excluded = (s.directApps + if (s.bypassTorrents) TORRENT_APPS else emptyList()).distinct()

        return buildJsonObject {
            putJsonObject("log") { put("level", "warn"); put("timestamp", true); if (logFile != null) put("output", logFile) }
            putJsonObject("experimental") {
                putJsonObject("clash_api") {
                    put("external_controller", "127.0.0.1:${control.port}")
                    put("secret", control.secret)
                }
                // libbox always opens a cache file; every core needs its own, or the second one waits forever
                if (cacheFile != null) putJsonObject("cache_file") { put("enabled", true); put("path", cacheFile) }
            }
            putJsonObject("dns") {
                put("servers", JsonArray(dnsServers))
                put("rules", JsonArray(dnsRules))
                put("strategy", "ipv4_only")
            }
            // IPv6: the tunnel claims the IPv6 route with a dummy ULA address and drops every IPv6 packet,
            // DNS answers IPv4 only; nothing leaks around the VPN over IPv6.
            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "tun")
                    put("tag", "tun-in")
                    put("address", strings(listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126")))
                    put("auto_route", true)
                    put("strict_route", true)
                    put("stack", "mixed")
                    put("endpoint_independent_nat", true)
                    if (excluded.isNotEmpty()) put("exclude_package", strings(excluded))
                }
            }
            putJsonObject("route") {
                put("auto_detect_interface", true)
                put("default_domain_resolver", "direct-dns")
                put("rules", JsonArray(routeRules))
                put("final", "proxy-out")
            }
            putJsonArray("outbounds") {
                add(buildProxyOutbound(proxy, "proxy-out", serverAddress))
                addJsonObject { put("type", "direct"); put("tag", "direct-out") }
            }
        }
    }

    private fun alpnList(text: String): JsonArray = strings(text.split(',').map { it.trim() }.filter { it.isNotEmpty() })

    private fun JsonObjectBuilder.wsTransport(proxy: ProxyItem, host: String) = putJsonObject("transport") {
        put("type", "ws")
        put("path", proxy.wsPath.ifEmpty { "/" })
        putJsonObject("headers") { put("Host", proxy.wsHost.ifEmpty { proxy.sni.ifEmpty { host } }) }
    }

    private fun JsonObjectBuilder.grpcTransport(proxy: ProxyItem) = putJsonObject("transport") {
        put("type", "grpc")
        put("service_name", proxy.grpcServiceName)
    }

    /**
     * The outbound for one server: protocol, TLS/Reality, transport. Shared by the tunnel ("proxy-out")
     * and the server check. [address] connects to that exact IP instead of resolving the server's name;
     * the name is still what the TLS handshake and headers carry.
     */
    fun buildProxyOutbound(proxy: ProxyItem, tag: String = "proxy-out", address: String? = null): JsonObject {
        val host = proxy.cleanHost
        val server = if (address.isNullOrBlank()) host else address
        val port = proxy.port
        val fp = proxy.fingerprint.ifEmpty { "chrome" }

        val outbound: JsonObject = when (proxy.protocol) {
            ProxyProtocol.Vless -> buildJsonObject {
                put("type", "vless"); put("tag", tag); put("server", server); put("server_port", port); put("uuid", proxy.uuid)
                if (proxy.flow.isNotEmpty()) put("flow", proxy.flow)

                val alpn = when {
                    proxy.alpn.isNotEmpty() -> alpnList(proxy.alpn)
                    proxy.transportType == "ws" -> strings(listOf("http/1.1"))
                    proxy.transportType == "h2" || proxy.transportType == "http" -> strings(listOf("h2"))
                    proxy.security == "reality" -> strings(listOf("h2", "http/1.1"))
                    else -> JsonArray(emptyList())
                }
                if (proxy.security == "reality") {
                    putJsonObject("tls") {
                        put("enabled", true)
                        put("server_name", proxy.sni.ifEmpty { host })
                        putJsonObject("reality") { put("enabled", true); put("public_key", proxy.publicKey); put("short_id", proxy.shortId) }
                        putJsonObject("utls") { put("enabled", true); put("fingerprint", fp) }
                        if (alpn.isNotEmpty()) put("alpn", alpn)
                    }
                } else if (proxy.security == "tls") {
                    putJsonObject("tls") {
                        put("enabled", true)
                        put("server_name", proxy.sni.ifEmpty { host })
                        put("insecure", false)
                        // Look like a browser, not a Go program: the plain Go TLS handshake is a known DPI signal
                        putJsonObject("utls") { put("enabled", true); put("fingerprint", fp) }
                        if (alpn.isNotEmpty()) put("alpn", alpn)
                    }
                }

                when (proxy.transportType) {
                    "ws" -> wsTransport(proxy, host)
                    "grpc" -> grpcTransport(proxy)
                    "httpupgrade" -> putJsonObject("transport") {
                        put("type", "httpupgrade"); put("path", proxy.wsPath.ifEmpty { "/" }); put("host", proxy.wsHost.ifEmpty { host })
                    }
                    "h2", "http" -> putJsonObject("transport") {
                        put("type", "http")
                        put("path", proxy.wsPath.ifEmpty { "/" })
                        put("host", strings(listOf(proxy.wsHost.ifEmpty { proxy.sni.ifEmpty { host } })))
                    }
                }
                keepAlive(proxy)
            }

            ProxyProtocol.Trojan -> buildJsonObject {
                put("type", "trojan"); put("tag", tag); put("server", server); put("server_port", port)
                put("password", proxy.password.ifEmpty { proxy.uuid })
                putJsonObject("tls") {
                    put("enabled", true)
                    put("server_name", proxy.sni.ifEmpty { host })
                    put("insecure", false)
                    putJsonObject("utls") { put("enabled", true); put("fingerprint", fp) }
                    put("alpn", if (proxy.alpn.isNotEmpty()) alpnList(proxy.alpn) else strings(listOf("h2", "http/1.1")))
                }
                when (proxy.transportType) {
                    "ws" -> wsTransport(proxy, host)
                    "grpc" -> grpcTransport(proxy)
                }
                keepAlive(proxy)
            }

            ProxyProtocol.Shadowsocks -> buildJsonObject {
                put("type", "shadowsocks"); put("tag", tag); put("server", server); put("server_port", port)
                put("method", if (proxy.security.isNotEmpty() && proxy.security != "none") proxy.security else "aes-256-gcm")
                put("password", proxy.password.ifEmpty { proxy.uuid })
                keepAlive(proxy)
            }

            ProxyProtocol.Vmess -> buildJsonObject {
                put("type", "vmess"); put("tag", tag); put("server", server); put("server_port", port)
                put("uuid", proxy.uuid); put("alter_id", 0)
                put("security", if (proxy.security.isNotEmpty() && proxy.security != "tls") proxy.security else "auto")
                if (proxy.security == "tls" || proxy.sni.isNotEmpty()) {
                    putJsonObject("tls") {
                        put("enabled", true)
                        put("server_name", proxy.sni.ifEmpty { host })
                        put("insecure", false)
                        putJsonObject("utls") { put("enabled", true); put("fingerprint", fp) }
                        if (proxy.alpn.isNotEmpty()) put("alpn", alpnList(proxy.alpn))
                    }
                }
                when (proxy.transportType) {
                    "ws" -> wsTransport(proxy, host)
                    "grpc" -> grpcTransport(proxy)
                }
                keepAlive(proxy)
            }

            ProxyProtocol.Hysteria2, ProxyProtocol.Tuic -> buildQuicOutbound(proxy, host, server, port, tag)

            ProxyProtocol.Socks5 -> buildJsonObject {
                put("type", "socks"); put("tag", tag); put("server", server); put("server_port", port)
                if (proxy.hasAuth) { put("username", proxy.username); put("password", proxy.password) }
                keepAlive(proxy)
            }

            else -> buildJsonObject {
                put("type", "http"); put("tag", tag); put("server", server); put("server_port", port)
                if (proxy.hasAuth) { put("username", proxy.username); put("password", proxy.password) }
                keepAlive(proxy)
            }
        }
        return outbound
    }

    private fun JsonObjectBuilder.keepAlive(proxy: ProxyItem) {
        if (!proxy.usesUdpTransport) {
            put("tcp_keep_alive", "15s")
            put("tcp_keep_alive_interval", "15s")
        }
    }

    /** Hysteria2 / TUIC: TLS always on, ALPN h3 unless the link says otherwise, no uTLS (it exists only for TCP). */
    private fun buildQuicOutbound(proxy: ProxyItem, host: String, server: String, port: Int, tag: String): JsonObject {
        val tls = buildJsonObject {
            put("enabled", true)
            put("server_name", proxy.sni.ifEmpty { host })
            put("insecure", proxy.insecure)
            put("alpn", alpnList(proxy.alpn.ifBlank { "h3" }))
        }
        if (proxy.protocol == ProxyProtocol.Hysteria2) {
            val ranges = hysteriaPortRanges(proxy.serverPorts)
            return buildJsonObject {
                put("type", "hysteria2"); put("tag", tag); put("server", server)
                // Port hopping: the list replaces the single port (sing-box treats the two as conflicting)
                if (ranges.isNotEmpty()) put("server_ports", strings(ranges)) else put("server_port", port)
                put("password", proxy.password)
                if (proxy.obfsPassword.isNotEmpty()) putJsonObject("obfs") { put("type", "salamander"); put("password", proxy.obfsPassword) }
                put("tls", tls)
            }
        }
        return buildJsonObject {
            put("type", "tuic"); put("tag", tag); put("server", server); put("server_port", port)
            put("uuid", proxy.uuid); put("password", proxy.password)
            if (proxy.congestionControl in setOf("bbr", "cubic", "new_reno")) put("congestion_control", proxy.congestionControl)
            if (proxy.udpRelayMode in setOf("native", "quic")) put("udp_relay_mode", proxy.udpRelayMode)
            put("tls", tls)
        }
    }

    /** Link-style port list ("443,20000-30000") to sing-box ranges ("443:443", "20000:30000"); invalid parts dropped. */
    fun hysteriaPortRanges(spec: String?): List<String> {
        val result = mutableListOf<String>()
        if (spec.isNullOrBlank()) return result
        for (token in spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val parts = token.split('-', ':')
            if (parts.size == 1) {
                val single = parts[0].toIntOrNull()
                if (single != null && single in 1..65535) result += "$single:$single"
            } else if (parts.size == 2) {
                val from = parts[0].toIntOrNull()
                val to = parts[1].toIntOrNull()
                if (from != null && to != null && from in 1..65535 && to in 1..65535 && from <= to) result += "$from:$to"
            }
        }
        return result.distinct()
    }

    /**
     * The server check's own config: one outbound per target (in order, so the core's "outbound[N]" errors
     * point straight at it), a control API, no inbounds. Its sockets bypass the running tunnel.
     */
    fun generateCheckConfig(targets: List<Pair<ProxyItem, String?>>, control: ControlEndpoint, logFile: String? = null, cacheFile: String? = null): JsonObject = buildJsonObject {
        putJsonObject("log") { put("level", "error"); if (logFile != null) put("output", logFile) }
        putJsonObject("dns") {
            putJsonArray("servers") { addJsonObject { put("type", "local"); put("tag", "dns") } }
            put("strategy", "ipv4_only")
        }
        put("outbounds", buildJsonArray {
            targets.forEachIndexed { i, (server, address) -> add(buildProxyOutbound(server, checkTag(i), address)) }
            addJsonObject { put("type", "direct"); put("tag", "direct") }
        })
        putJsonObject("route") {
            put("auto_detect_interface", true)
            put("default_domain_resolver", "dns")
            put("final", "direct")
        }
        putJsonObject("experimental") {
            putJsonObject("clash_api") {
                put("external_controller", "127.0.0.1:${control.port}")
                put("secret", control.secret)
            }
            if (cacheFile != null) putJsonObject("cache_file") { put("enabled", true); put("path", cacheFile) }
        }
    }

    fun checkTag(index: Int) = "s$index"

    /** Index N from the core's "initialize outbound[N]: ..." error, or null. */
    fun parseBadOutbound(coreError: String?): Int? = Regex("outbound\\[(\\d+)]").find(coreError ?: "")?.groupValues?.get(1)?.toIntOrNull()

    /** The core reports a port it could not take as "listen tcp ...: bind: ...". */
    fun isPortConflict(coreError: String?) = !coreError.isNullOrEmpty() && coreError.contains("bind:", ignoreCase = true)
}
