package com.zion.app.core

import com.zion.app.model.ProxyItem
import com.zion.app.model.ProxyProtocol
import com.zion.app.model.ProxyStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI

/** Share links, bulk lists and subscription bodies → servers. Same rules as Windows ProxyParser. */
object ProxyParser {

    fun parseSingle(input: String, defaultName: String = "Импортированный прокси"): ProxyItem? {
        val item = parseSingleInternal(input, defaultName) ?: return null
        item.ensureDeterministicId()
        item.autoEnrichCountryIfMissing()
        return item
    }

    private fun parseSingleInternal(raw: String, defaultName: String): ProxyItem? {
        if (raw.isBlank()) return null
        var input = raw.trim().trim('"', '\'', '`')

        when {
            input.startsWith("vless://", true) -> return parseVless(input)
            input.startsWith("trojan://", true) -> return parseTrojan(input)
            input.startsWith("ss://", true) -> return parseShadowsocks(input)
            input.startsWith("vmess://", true) -> return parseVmess(input)
            input.startsWith("hysteria2://", true) || input.startsWith("hy2://", true) -> return parseHysteria2(input)
            input.startsWith("tuic://", true) -> return parseTuic(input)
        }

        var name = defaultName
        val hashIdx = input.indexOf('#')
        if (hashIdx != -1) {
            name = unescapeData(input.substring(hashIdx + 1).trim())
            input = input.substring(0, hashIdx).trim()
        }

        var protocol = ProxyProtocol.Http
        when {
            input.startsWith("socks5://", true) || input.startsWith("socks://", true) -> {
                protocol = ProxyProtocol.Socks5
                input = if (input.startsWith("socks5://", true)) input.substring(9) else input.substring(8)
            }
            input.startsWith("https://", true) -> input = input.substring(8)
            input.startsWith("http://", true) -> input = input.substring(7)
        }

        var host = ""
        var port = 8080
        var user = ""
        var pass = ""

        if (input.contains('@')) {
            val authPart = input.substringBefore('@')
            val hostPart = input.substringAfter('@').split('?', '/')[0].trim()
            if (authPart.contains(':')) {
                user = unescapeData(authPart.substringBefore(':'))
                pass = unescapeData(authPart.substringAfter(':'))
            } else user = unescapeData(authPart)

            if (hostPart.contains(':')) {
                host = hostPart.substringBefore(':')
                port = hostPart.substringAfter(':').trimEnd('/').toIntOrNull() ?: 0
            } else host = hostPart.trimEnd('/')
        } else {
            val cleanInput = input.split('?', '/')[0].trim()
            val tokens = cleanInput.split(':', ';', '\t', ' ').filter { it.isNotEmpty() }
            when {
                tokens.size >= 4 -> { host = tokens[0]; port = tokens[1].toIntOrNull() ?: 0; user = tokens[2]; pass = tokens[3] }
                tokens.size == 2 -> { host = tokens[0]; port = tokens[1].toIntOrNull() ?: 0 }
                tokens.size == 3 -> { host = tokens[0]; port = tokens[1].toIntOrNull() ?: 0; user = tokens[2] }
            }
        }

        if (host.isBlank() || port <= 0 || port > 65535) return null

        return ProxyItem(
            name = if (name.isNotBlank() && name != defaultName) name else (if (user.isEmpty()) "$host:$port" else "$user@$host"),
            host = host, port = port, protocol = protocol, username = user, password = pass,
        )
    }

    fun parseBulk(text: String): MutableList<ProxyItem> {
        val result = mutableListOf<ProxyItem>()
        if (text.isBlank()) return result
        var working = text.trim()

        if (working.startsWith('{') || working.startsWith('[')) {
            val json = parseJsonSubscription(working)
            if (json.isNotEmpty()) return json
        }

        if (!working.contains('\n') && !working.contains("://") && working.length > 20) {
            val decoded = tryDecodeBase64(working)
            if (decoded != null) {
                val s = String(decoded, Charsets.UTF_8).trim()
                if (s.startsWith('{') || s.startsWith('[')) {
                    val json = parseJsonSubscription(s)
                    if (json.isNotEmpty()) return json
                } else if (s.contains("://") || s.contains('\n')) {
                    working = s
                }
            }
        }

        var index = 1
        for (rawLine in working.split('\r', '\n')) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith("//")) continue
            val item = parseSingle(line, "Прокси #$index")
            if (item != null) {
                result += item
                index++
            }
        }
        return result
    }

    fun parseJsonSubscription(jsonText: String): MutableList<ProxyItem> {
        val proxies = mutableListOf<ProxyItem>()
        if (jsonText.isBlank()) return proxies
        try {
            when (val root = Json.parseToJsonElement(jsonText)) {
                is JsonArray -> for (elem in root) {
                    val obj = elem as? JsonObject ?: continue
                    val outbounds = obj["outbounds"] as? JsonArray
                    if (outbounds != null) {
                        val remarks = obj.str("remarks")
                        for (ob in outbounds) parseSingBoxOutbound(ob, remarks)?.let { proxies += it }
                    } else parseSingBoxOutbound(obj, "")?.let { proxies += it }
                }
                is JsonObject -> {
                    val list = (root["outbounds"] as? JsonArray) ?: (root["proxies"] as? JsonArray)
                    list?.forEach { ob -> parseSingBoxOutbound(ob, "")?.let { proxies += it } }
                }
                else -> Unit
            }
        } catch (_: Exception) {
        }
        return proxies
    }

    private fun JsonObject.str(name: String): String = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    private fun JsonObject.anyStr(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    private fun parseSingBoxOutbound(element: JsonElement, defaultName: String): ProxyItem? {
        val ob = element as? JsonObject ?: return null
        val type = (ob.anyStr("type") ?: ob.anyStr("protocol") ?: "").lowercase()
        if (type == "direct" || type == "block" || type == "dns") return null

        val server = ob.anyStr("server") ?: ob.anyStr("address") ?: ""
        if (server.isBlank()) return null

        val port = ob.int("server_port") ?: ob.int("port") ?: 443
        var tag = ob.anyStr("tag") ?: ob.anyStr("name") ?: ""
        if (tag.isBlank()) tag = defaultName.ifBlank { "$server:$port" }

        val proto = when (type) {
            "vless" -> ProxyProtocol.Vless
            "trojan" -> ProxyProtocol.Trojan
            "shadowsocks" -> ProxyProtocol.Shadowsocks
            "vmess" -> ProxyProtocol.Vmess
            "socks", "socks5" -> ProxyProtocol.Socks5
            "hysteria2" -> ProxyProtocol.Hysteria2
            "tuic" -> ProxyProtocol.Tuic
            else -> return null
        }

        val uuid = ob.anyStr("uuid") ?: ob.anyStr("password") ?: ""
        val flow = ob.anyStr("flow") ?: ""
        var security = "none"
        var sni = ""
        var pbk = ""
        var sid = ""
        var fp = "chrome"

        val tls = ob["tls"] as? JsonObject
        if (tls != null) {
            security = "tls"
            sni = tls.anyStr("server_name") ?: ""
            (tls["utls"] as? JsonObject)?.anyStr("fingerprint")?.let { fp = it }
            val reality = tls["reality"] as? JsonObject
            if (reality != null) {
                security = "reality"
                pbk = reality.anyStr("public_key") ?: ""
                sid = reality.anyStr("short_id") ?: ""
            }
        }

        var transportType = "tcp"
        var wsPath = "/"
        var grpc = ""
        (ob["transport"] as? JsonObject)?.let { tr ->
            transportType = (tr.anyStr("type") ?: "tcp").lowercase()
            tr.anyStr("path")?.let { wsPath = it }
            tr.anyStr("service_name")?.let { grpc = it }
        }

        val item = ProxyItem(
            name = tag, host = server, port = port, protocol = proto, uuid = uuid, password = uuid,
            security = security, sni = sni, flow = flow, publicKey = pbk, shortId = sid, fingerprint = fp,
            transportType = transportType, wsPath = wsPath, grpcServiceName = grpc,
        )

        if (proto == ProxyProtocol.Hysteria2 || proto == ProxyProtocol.Tuic) {
            item.transportType = ""
            item.fingerprint = ""
            item.password = ob.str("password")
            item.uuid = if (proto == ProxyProtocol.Tuic) ob.str("uuid") else ""
            item.congestionControl = ob.str("congestion_control")
            item.udpRelayMode = ob.str("udp_relay_mode")
            (ob["obfs"] as? JsonObject)?.let { item.obfsPassword = it.str("password") }
            ob["server_ports"]?.let { ports ->
                // sing-box writes ranges as "20000:30000", links as "20000-30000"
                val ranges = when (ports) {
                    is JsonArray -> ports.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    is JsonPrimitive -> listOfNotNull(ports.contentOrNull)
                    else -> emptyList()
                }
                item.serverPorts = ranges.filter { it.isNotEmpty() }.joinToString(",") { it.replace(':', '-') }
            }
            if (tls != null) {
                item.insecure = (tls["insecure"] as? JsonPrimitive)?.booleanOrNull == true
                when (val a = tls["alpn"]) {
                    is JsonArray -> item.alpn = a.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotEmpty() }.joinToString(",")
                    is JsonPrimitive -> item.alpn = a.contentOrNull ?: ""
                    else -> Unit
                }
            }
        }

        item.ensureDeterministicId()
        item.autoEnrichCountryIfMissing()
        return item
    }

    /** Splits "host:port" / "[v6]:port" (port optional); null if broken. */
    private fun splitHostPort(part: String, defaultPort: Int): Pair<String, Int>? {
        var port = defaultPort
        val host: String
        if (part.startsWith('[')) {
            val close = part.indexOf(']')
            if (close == -1) return null
            host = part.substring(1, close)
            val after = part.substring(close + 1)
            if (after.startsWith(':')) port = after.substring(1).trimEnd('/').toIntOrNull() ?: 0
        } else if (part.contains(':')) {
            host = part.substringBefore(':')
            port = part.substringAfter(':').trimEnd('/').toIntOrNull() ?: 0
        } else host = part.trimEnd('/')
        return host to port
    }

    fun parseVless(link: String): ProxyItem? {
        if (!link.startsWith("vless://", true)) return null
        return try {
            var raw = link.substring(8)
            var name = "VLESS Сервер"
            val hashIdx = raw.indexOf('#')
            if (hashIdx != -1) {
                name = unescapeData(raw.substring(hashIdx + 1).trim())
                raw = raw.substring(0, hashIdx)
            }
            val at = raw.indexOf('@')
            if (at == -1) return null
            val uuid = raw.substring(0, at).trim()
            val rest = raw.substring(at + 1)
            val q = rest.indexOf('?')
            val hostPort = if (q != -1) rest.substring(0, q) else rest
            val query = if (q != -1) rest.substring(q + 1) else ""
            val (host, port) = splitHostPort(hostPort, 443) ?: return null
            if (host.isBlank() || port <= 0 || port > 65535) return null

            val p = parseQuery(query)
            val type = p.getOr("type", p.getOr("net", "tcp")).lowercase()
            val path = p.getOr("path", "")
            var serviceName = p.getOr("serviceName", p.getOr("service_name", ""))
            if (serviceName.isEmpty() && type == "grpc" && path.isNotEmpty()) serviceName = path

            ProxyItem(
                name = name.ifBlank { "$host:$port" }, host = host, port = port, protocol = ProxyProtocol.Vless,
                uuid = uuid, flow = p.getOr("flow", ""), security = p.getOr("security", "none").lowercase(),
                sni = p.getOr("sni", p.getOr("peer", "")),
                fingerprint = p.getOr("fp", p.getOr("fingerprint", "chrome")),
                publicKey = p.getOr("pbk", p.getOr("publicKey", p.getOr("public_key", ""))),
                shortId = p.getOr("sid", p.getOr("shortId", p.getOr("short_id", ""))),
                spiderX = p.getOr("spx", ""), transportType = type, wsPath = path, wsHost = p.getOr("host", ""),
                grpcServiceName = serviceName, alpn = p.getOr("alpn", ""), rawLink = link,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseTrojan(link: String): ProxyItem? {
        if (!link.startsWith("trojan://", true)) return null
        return try {
            var raw = link.substring(9)
            var name = "Trojan Сервер"
            val hashIdx = raw.indexOf('#')
            if (hashIdx != -1) {
                name = unescapeData(raw.substring(hashIdx + 1).trim())
                raw = raw.substring(0, hashIdx)
            }
            val at = raw.indexOf('@')
            if (at == -1) return null
            val password = unescapeData(raw.substring(0, at).trim())
            val rest = raw.substring(at + 1)
            val q = rest.indexOf('?')
            val hostPort = if (q != -1) rest.substring(0, q) else rest
            val query = if (q != -1) rest.substring(q + 1) else ""
            val (host, port) = splitHostPort(hostPort, 443) ?: return null
            if (host.isBlank() || port <= 0 || port > 65535) return null

            val p = parseQuery(query)
            val type = p.getOr("type", p.getOr("net", "tcp")).lowercase()
            val path = p.getOr("path", "")
            var serviceName = p.getOr("serviceName", p.getOr("service_name", ""))
            if (serviceName.isEmpty() && type == "grpc" && path.isNotEmpty()) serviceName = path

            ProxyItem(
                name = name.ifBlank { "$host:$port" }, host = host, port = port, protocol = ProxyProtocol.Trojan,
                password = password, uuid = password, security = "tls", sni = p.getOr("sni", p.getOr("peer", "")),
                fingerprint = p.getOr("fp", p.getOr("fingerprint", "chrome")), transportType = type, wsPath = path,
                wsHost = p.getOr("host", ""), grpcServiceName = serviceName, alpn = p.getOr("alpn", ""), rawLink = link,
            )
        } catch (_: Exception) {
            null
        }
    }

    private class QuicParts(val auth: String, val host: String, val portSpec: String, val query: Map<String, String>, val name: String)

    /** Splits "auth@host:ports/?query#name" (the part after the scheme) of a Hysteria2/TUIC link. */
    private fun splitQuicLink(rawIn: String): QuicParts? {
        var raw = rawIn
        var name = ""
        val hashIdx = raw.indexOf('#')
        if (hashIdx != -1) {
            name = unescapeData(raw.substring(hashIdx + 1).trim())
            raw = raw.substring(0, hashIdx)
        }
        val q = raw.indexOf('?')
        val main = if (q != -1) raw.substring(0, q) else raw
        val query = if (q != -1) parseQuery(raw.substring(q + 1)) else parseQuery("")
        val at = main.lastIndexOf('@')
        if (at <= 0) return null
        val auth = unescapeData(main.substring(0, at))
        val hostPort = main.substring(at + 1).trimEnd('/')
        val host: String
        var portSpec = ""
        if (hostPort.startsWith('[')) {
            val close = hostPort.indexOf(']')
            if (close == -1) return null
            host = hostPort.substring(1, close)
            val after = hostPort.substring(close + 1)
            if (after.startsWith(':')) portSpec = after.substring(1)
        } else {
            val colon = hostPort.indexOf(':')
            host = if (colon != -1) hostPort.substring(0, colon) else hostPort
            if (colon != -1) portSpec = hostPort.substring(colon + 1)
        }
        if (host.isBlank() || auth.isEmpty()) return null
        return QuicParts(auth, host, portSpec, query, name)
    }

    private fun isTrue(v: String) = v == "1" || v.equals("true", true)

    /**
     * hysteria2://password@host:port/?sni=..&obfs=salamander&obfs-password=..&insecure=1#name (also hy2://).
     * The port part may list several ports and ranges ("443,20000-30000") for port hopping; so may mport=.
     */
    fun parseHysteria2(link: String): ProxyItem? {
        val scheme = when {
            link.startsWith("hy2://", true) -> "hy2://"
            link.startsWith("hysteria2://", true) -> "hysteria2://"
            else -> return null
        }
        return try {
            val parts = splitQuicLink(link.substring(scheme.length)) ?: return null
            val ports = parts.portSpec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            ports += parts.query.getOr("mport", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val distinct = ports.distinct()

            var port = 443
            val single = distinct.firstOrNull { !it.contains('-') }
            val first = single ?: distinct.firstOrNull()
            if (first != null) port = first.split('-')[0].toIntOrNull() ?: return null
            if (port <= 0 || port > 65535) return null
            val hopping = distinct.size > 1 || distinct.any { it.contains('-') }

            val obfs = parts.query.getOr("obfs", "")
            val obfsPassword = parts.query.getOr("obfs-password", parts.query.getOr("obfs_password", ""))
            ProxyItem(
                name = parts.name.ifBlank { "${parts.host}:$port" }, host = parts.host, port = port,
                protocol = ProxyProtocol.Hysteria2, password = parts.auth, security = "tls",
                sni = parts.query.getOr("sni", parts.query.getOr("peer", "")),
                obfsPassword = if (obfs.isEmpty() || obfs.equals("salamander", true)) obfsPassword else "",
                serverPorts = if (hopping) distinct.joinToString(",") else "",
                insecure = isTrue(parts.query.getOr("insecure", parts.query.getOr("allowInsecure", ""))),
                alpn = parts.query.getOr("alpn", ""), transportType = "", fingerprint = "", rawLink = link,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** tuic://uuid:password@host:port?congestion_control=bbr&udp_relay_mode=native&alpn=h3&sni=..#name */
    fun parseTuic(link: String): ProxyItem? {
        if (!link.startsWith("tuic://", true)) return null
        return try {
            val parts = splitQuicLink(link.substring(7)) ?: return null
            val colon = parts.auth.indexOf(':')
            if (colon <= 0) return null // TUIC v5 needs both the UUID and the password
            var port = 443
            if (parts.portSpec.isNotEmpty()) port = parts.portSpec.toIntOrNull() ?: return null
            if (port <= 0 || port > 65535) return null
            val q = parts.query
            ProxyItem(
                name = parts.name.ifBlank { "${parts.host}:$port" }, host = parts.host, port = port,
                protocol = ProxyProtocol.Tuic, uuid = parts.auth.substring(0, colon).trim(),
                password = parts.auth.substring(colon + 1), security = "tls", sni = q.getOr("sni", q.getOr("peer", "")),
                congestionControl = q.getOr("congestion_control", q.getOr("congestion-control", "")).lowercase(),
                udpRelayMode = q.getOr("udp_relay_mode", q.getOr("udp-relay-mode", "")).lowercase(),
                insecure = isTrue(q.getOr("allow_insecure", q.getOr("insecure", q.getOr("allowInsecure", "")))),
                alpn = q.getOr("alpn", ""), transportType = "", fingerprint = "", rawLink = link,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseShadowsocks(link: String): ProxyItem? {
        if (!link.startsWith("ss://", true)) return null
        return try {
            var raw = link.substring(5)
            var name = "Shadowsocks"
            val hashIdx = raw.indexOf('#')
            if (hashIdx != -1) {
                name = unescapeData(raw.substring(hashIdx + 1).trim())
                raw = raw.substring(0, hashIdx)
            }
            var method = "aes-256-gcm"
            var password: String
            var host: String
            var port = 8388

            if (raw.contains('@')) {
                // ss://BASE64(method:password)@host:port or ss://method:password@host:port
                val authPart = raw.substringBefore('@')
                val hostPart = raw.substringAfter('@').split('?', '/')[0].trim()
                val authDecoded = tryDecodeBase64(authPart)?.let { String(it, Charsets.UTF_8) } ?: authPart
                if (authDecoded.contains(':')) {
                    method = authDecoded.substringBefore(':')
                    password = authDecoded.substringAfter(':')
                } else password = authDecoded

                if (hostPart.startsWith('[')) {
                    val close = hostPart.indexOf(']')
                    host = hostPart.substring(1, close)
                    if (hostPart.length > close + 1 && hostPart[close + 1] == ':') port = hostPart.substring(close + 2).toIntOrNull() ?: 0
                } else if (hostPart.contains(':')) {
                    host = hostPart.substringBefore(':')
                    port = hostPart.substringAfter(':').toIntOrNull() ?: 0
                } else host = hostPart
            } else {
                // ss://BASE64(method:password@host:port)
                val decoded = tryDecodeBase64(raw) ?: return null
                return parseShadowsocks("ss://" + String(decoded, Charsets.UTF_8) + (if (name.isNotEmpty()) "#" + escapeData(name) else ""))
            }

            if (host.isBlank() || port <= 0 || port > 65535) return null
            ProxyItem(
                name = name.ifBlank { "$host:$port" }, host = host, port = port, protocol = ProxyProtocol.Shadowsocks,
                password = password, security = method, rawLink = link,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseVmess(link: String): ProxyItem? {
        if (!link.startsWith("vmess://", true)) return null
        return try {
            val decoded = tryDecodeBase64(link.substring(8).trim()) ?: return null
            val root = Json.parseToJsonElement(String(decoded, Charsets.UTF_8)) as? JsonObject ?: return null
            fun s(n: String, d: String = "") = (root[n] as? JsonPrimitive)?.contentOrNull ?: d
            fun i(n: String, d: Int): Int {
                val p = root[n] as? JsonPrimitive ?: return d
                return p.intOrNull ?: p.contentOrNull?.toIntOrNull() ?: d
            }
            val host = s("add")
            val port = i("port", 443)
            if (host.isBlank() || port <= 0 || port > 65535) return null
            val net = s("net", "tcp")
            val tls = s("tls", "none")
            val wsHost = s("host")
            val path = s("path")
            val sni = s("sni")
            ProxyItem(
                name = s("ps").ifBlank { "$host:$port" }, host = host, port = port, protocol = ProxyProtocol.Vmess,
                uuid = s("id"), alterId = i("aid", 0), security = if (tls.equals("tls", true)) "tls" else s("scy", "auto"),
                transportType = net.lowercase(), sni = sni.ifEmpty { wsHost }, wsHost = wsHost, wsPath = path,
                grpcServiceName = if (net.equals("grpc", true)) path else "", alpn = s("alpn"),
                fingerprint = s("fp", "chrome"), rawLink = link,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun extractActualSubscriptionUrl(input: String): String {
        if (input.isBlank()) return ""
        var clean = input.trim().trim('"', '\'', '`', '<', '>')

        if (clean.startsWith("sub://", true)) {
            tryDecodeBase64(clean.substring(6).trim())?.let {
                val d = String(it, Charsets.UTF_8).trim()
                if (d.startsWith("http://", true) || d.startsWith("https://", true)) return d
            }
        }
        if (clean.contains("%3A", true) || clean.contains("%2F", true)) clean = try { unescapeData(clean) } catch (_: Exception) { clean }

        val urlParam = clean.indexOf("url=", ignoreCase = true)
        if (urlParam != -1) {
            var sub = clean.substring(urlParam + 4)
            sub = sub.substringBefore('&').substringBefore('#').trim()
            if (sub.startsWith("http://", true) || sub.startsWith("https://", true)) return sub
        }

        var httpIdx = clean.indexOf("https://", ignoreCase = true)
        if (httpIdx == -1) httpIdx = clean.indexOf("http://", ignoreCase = true)
        if (httpIdx != -1) return clean.substring(httpIdx).trim().substringBefore(' ')
        return clean
    }

    fun isSubscriptionUrl(input: String): Boolean {
        if (input.isBlank()) return false
        val normalized = extractActualSubscriptionUrl(input)
        if (normalized.isBlank()) return false
        if (!normalized.startsWith("http://", true) && !normalized.startsWith("https://", true)) return false
        if (normalized.contains('@')) return false // user:pass@host:port = a single proxy

        val lower = normalized.lowercase()
        val marks = listOf("/sub", "sub.", "subscription", "token=", "key=", "/api/v1/client/subscribe", "/api/v1/client/",
            "/subscribe", "/download", "/clash", "/sing-box", "/v2ray", "/xray")
        if (marks.any { lower.contains(it) }) return true

        return try {
            val uri = URI(normalized)
            val h = (uri.host ?: "").lowercase()
            if (h.contains("sub") || h.contains("subscribe") || h.contains("feed")) return true
            val pathAndQuery = (uri.rawPath ?: "") + (uri.rawQuery?.let { "?$it" } ?: "")
            pathAndQuery.length > 1 || (uri.rawQuery?.length ?: 0) > 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reads the de-facto standard header "subscription-userinfo: upload=1; download=2; total=3; expire=1700000000".
     * Unknown or broken parts are skipped; expire=0 means "no end date". Expire comes back as epoch ms.
     */
    fun parseSubscriptionUserInfo(header: String?): LongArray {
        var up = 0L
        var down = 0L
        var total = 0L
        var expire = -1L
        if (header.isNullOrBlank()) return longArrayOf(up, down, total, expire)
        for (part in header.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = part.substring(0, eq).trim().lowercase()
            val value = part.substring(eq + 1).trim().toLongOrNull() ?: continue
            if (value < 0) continue
            when (key) {
                "upload" -> up = value
                "download" -> down = value
                "total" -> total = value
                "expire" -> if (value in 1 until 253402300800L) expire = value * 1000
            }
        }
        return longArrayOf(up, down, total, expire)
    }

    /** "profile-title" is plain text or "base64:..." (that is how non-Latin names are sent). */
    fun decodeProfileTitle(header: String?): String {
        if (header.isNullOrBlank()) return ""
        var value = header.trim()
        if (value.startsWith("base64:", true)) value = tryDecodeBase64(value.substring(7).trim())?.let { String(it, Charsets.UTF_8) } ?: ""
        value = value.trim()
        return if (value.length > 60) value.substring(0, 60) else value
    }

    /** Marks fresh subscription servers as such (owner link, stable ID, country). */
    fun adoptForSubscription(items: List<ProxyItem>, url: String) {
        for (item in items) {
            item.isFromSubscription = true
            item.subscriptionUrl = url
            item.ensureDeterministicId()
            item.autoEnrichCountryIfMissing()
            item.status = ProxyStatus.Unknown
        }
    }
}
