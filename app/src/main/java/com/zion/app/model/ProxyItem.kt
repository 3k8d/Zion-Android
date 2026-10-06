package com.zion.app.model

import com.zion.app.core.Countries
import com.zion.app.core.base64
import com.zion.app.core.escapeData
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID

/**
 * One server. Fields are mutable like the Windows ProxyItem: the list, the selection and the check results
 * all point at the same object, and the UI is told to redraw after changes (see MainViewModel.bump).
 */
@Serializable
class ProxyItem(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "Прокси",
    var host: String = "",
    var port: Int = 8080,
    var protocol: ProxyProtocol = ProxyProtocol.Vless,
    var username: String = "",
    var password: String = "",
    var country: String = "",
    var isFromSubscription: Boolean = false,
    var subscriptionUrl: String = "",
    var uuid: String = "",
    var flow: String = "",
    var security: String = "reality",
    var sni: String = "",
    var fingerprint: String = "chrome",
    var publicKey: String = "",
    var shortId: String = "",
    var spiderX: String = "",
    var transportType: String = "tcp",
    var wsPath: String = "",
    var wsHost: String = "",
    var grpcServiceName: String = "",
    var alpn: String = "",
    var rawLink: String = "",
    var alterId: Int = 0,
    // Hysteria2 / TUIC (both run over QUIC, i.e. UDP)
    /** Hysteria2 "salamander" obfuscation password; empty = no obfuscation. */
    var obfsPassword: String = "",
    /** Hysteria2 port hopping, as in links: "20000-30000" or "443,20000-30000". */
    var serverPorts: String = "",
    /** Accept the server's certificate without checking it (self-signed Hysteria2/TUIC servers). */
    var insecure: Boolean = false,
    /** TUIC: bbr, cubic or new_reno; empty = the core's default. */
    var congestionControl: String = "",
    /** TUIC: native or quic; empty = the core's default. */
    var udpRelayMode: String = "",
    /** Marked with a star by the user: shown first in the list and tried first on failover. */
    var isFavorite: Boolean = false,
) {
    // Status and pingMs come from the server check: they describe this run only and are not saved,
    // so an old result is never shown as current after a restart.
    @Transient var status: ProxyStatus = ProxyStatus.Unknown

    /** Delay of the last successful check in ms (a real page through the server), -1 if unknown. */
    @Transient var pingMs: Int = -1

    /**
     * The address that passed the last check. A server name often stands for several addresses and some
     * of them may be dead or blocked, so the tunnel connects to this one directly (the name still goes
     * into the TLS handshake). Null: resolve the name as usual.
     */
    @Transient var workingAddress: String? = null

    /** Hysteria2 and TUIC talk to the server over UDP, so a TCP check says nothing about them. */
    val usesUdpTransport: Boolean get() = protocol == ProxyProtocol.Hysteria2 || protocol == ProxyProtocol.Tuic

    /** The name without the flag emoji in front ("🇬🇧 Великобритания N1" -> "Великобритания N1"): the flag is drawn next to it. */
    val cleanName: String
        get() {
            val sb = StringBuilder(name.length)
            var i = 0
            while (i < name.length) {
                val cp = name.codePointAt(i)
                val len = Character.charCount(cp)
                if (cp !in 0x1F1E6..0x1F1FF) sb.append(name, i, i + len)
                i += len
            }
            val rest = sb.toString().replace(Regex("\\s{2,}"), " ").trim()
            return rest.ifEmpty { cleanCountryName }
        }

    /**
     * A fake entry some providers put into the list as a section title ("❗️Белые списки ниже").
     * Recognised only when it both starts with such a mark and speaks of what is below/above.
     */
    val isDivider: Boolean
        get() {
            val n = name.trim()
            return DIVIDER_MARKS.any { n.startsWith(it) } && DIVIDER_WORDS.any { n.contains(it, ignoreCase = true) }
        }

    /** The divider's text without its leading marks ("Белые списки ниже"). */
    val dividerTitle: String
        get() {
            val strip = DIVIDER_MARKS.joinToString("").toSet() + setOf('️', ' ')
            return name.trimStart { it in strip }.trim()
        }

    val displayName: String get() = name.ifBlank { cleanCountryName }

    val cleanHost: String
        get() {
            val h = host.trim()
            return if (h.contains(':')) h.split(':')[0] else h
        }

    val hasAuth: Boolean get() = username.isNotEmpty() || password.isNotEmpty()

    val displayAddress: String get() = "$cleanHost:$port"

    val protocolBadge: String
        get() {
            val transport = if (transportType.isNotEmpty()) transportType.trim().lowercase() else "tcp"
            return when (protocol) {
                ProxyProtocol.Vless -> when (transport) {
                    "grpc" -> "VLESS / gRPC"
                    "ws" -> "VLESS / WS"
                    "http", "h2" -> "VLESS / H2"
                    "httpupgrade" -> "VLESS / HTTPUpgrade"
                    else -> "VLESS / TCP"
                }
                ProxyProtocol.Trojan -> when (transport) {
                    "grpc" -> "Trojan / gRPC"
                    "ws" -> "Trojan / WS"
                    else -> "Trojan"
                }
                ProxyProtocol.Shadowsocks -> if (security.isNotEmpty() && security != "none") "SS / $security" else "Shadowsocks"
                ProxyProtocol.Vmess -> when (transport) {
                    "ws" -> "VMess / WS"
                    "grpc" -> "VMess / gRPC"
                    else -> "VMess"
                }
                ProxyProtocol.Socks5 -> "SOCKS5"
                ProxyProtocol.Hysteria2 -> "Hysteria2"
                ProxyProtocol.Tuic -> "TUIC"
                else -> "HTTP"
            }
        }

    val isAutoRoutingNode: Boolean
        get() = name.contains("Авто выбор", true) || name.contains("Автовыбор", true) || name.contains("Auto", true) ||
            cleanHost.startsWith("auto.", true)

    val cleanCountryName: String
        get() {
            if (isAutoRoutingNode) return "Автоматический выбор"
            if (country.isNotBlank() && country.contains(',')) return country
            val iso = Countries.resolveIsoCode(name, country, cleanHost)
            if (iso.isNotEmpty() && iso != "un" && iso != "auto") return Countries.nameRu(iso)
            if (country.isNotBlank()) {
                var c = country.trim()
                while (c.isNotEmpty() && (Character.isSurrogate(c[0]) || c.startsWith("🌐"))) {
                    c = if (Character.isSurrogate(c[0])) (if (c.length >= 2) c.substring(2).trim() else "") else c.substring(1).trim()
                }
                return c.ifBlank { country }
            }
            return "Локация не определена"
        }

    val countryCode: String get() = Countries.resolveIsoCode(name, country, cleanHost)

    val locationShort: String get() = cleanCountryName

    fun autoEnrichCountryIfMissing() {
        if (isAutoRoutingNode) {
            if (country.isBlank() || country == "Локация не определена") country = "Автоматический выбор"
            return
        }
        if (country.isBlank() || country == "Локация не определена" || country.length == 2) {
            val iso = Countries.resolveIsoCode(name, country, cleanHost)
            if (iso.isNotEmpty() && iso != "un") country = Countries.nameRu(iso)
        }
    }

    fun toShareableUrl(): String {
        val tag = if (name.isNotEmpty()) "#" + escapeData(name) else ""
        return when (protocol) {
            ProxyProtocol.Vless -> {
                val q = mutableListOf<String>()
                if (transportType.isNotEmpty()) q += "type=" + escapeData(transportType.lowercase())
                if (security.isNotEmpty()) q += "security=" + escapeData(security.lowercase())
                if (publicKey.isNotEmpty()) q += "pbk=" + escapeData(publicKey)
                if (fingerprint.isNotEmpty()) q += "fp=" + escapeData(fingerprint.lowercase())
                if (sni.isNotEmpty()) q += "sni=" + escapeData(sni)
                if (grpcServiceName.isNotEmpty()) q += "serviceName=" + escapeData(grpcServiceName)
                else if (wsPath.isNotEmpty()) q += "path=" + escapeData(wsPath)
                val query = if (q.isNotEmpty()) "?" + q.joinToString("&") else ""
                "vless://$uuid@$cleanHost:$port$query$tag"
            }
            ProxyProtocol.Trojan -> {
                val q = mutableListOf<String>()
                if (transportType.isNotEmpty()) q += "type=" + escapeData(transportType.lowercase())
                if (sni.isNotEmpty()) q += "sni=" + escapeData(sni)
                if (grpcServiceName.isNotEmpty()) q += "serviceName=" + escapeData(grpcServiceName)
                else if (wsPath.isNotEmpty()) q += "path=" + escapeData(wsPath)
                val query = if (q.isNotEmpty()) "?" + q.joinToString("&") else ""
                val pass = password.ifEmpty { uuid }
                "trojan://${escapeData(pass)}@$cleanHost:$port$query$tag"
            }
            ProxyProtocol.Shadowsocks -> {
                val method = if (security.isNotEmpty() && security != "none") security else "aes-256-gcm"
                val pass = password.ifEmpty { uuid }
                "ss://${base64("$method:$pass".toByteArray(Charsets.UTF_8))}@$cleanHost:$port$tag"
            }
            ProxyProtocol.Vmess -> {
                val obj = buildJsonObject {
                    put("v", "2")
                    put("ps", name)
                    put("add", cleanHost)
                    put("port", port)
                    put("id", uuid)
                    put("aid", alterId)
                    put("scy", if (security.isNotEmpty() && security != "tls") security else "auto")
                    put("net", transportType.ifEmpty { "tcp" })
                    put("type", "none")
                    put("host", wsHost)
                    put("path", grpcServiceName.ifEmpty { wsPath })
                    put("tls", if (security == "tls" || sni.isNotEmpty()) "tls" else "none")
                    put("sni", sni)
                }
                "vmess://" + base64(obj.toString().toByteArray(Charsets.UTF_8))
            }
            ProxyProtocol.Hysteria2 -> {
                val q = mutableListOf<String>()
                if (sni.isNotEmpty()) q += "sni=" + escapeData(sni)
                if (obfsPassword.isNotEmpty()) {
                    q += "obfs=salamander"
                    q += "obfs-password=" + escapeData(obfsPassword)
                }
                if (serverPorts.isNotEmpty()) q += "mport=" + escapeData(serverPorts)
                if (insecure) q += "insecure=1"
                val query = if (q.isNotEmpty()) "?" + q.joinToString("&") else ""
                "hysteria2://${escapeData(password)}@$cleanHost:$port/$query$tag"
            }
            ProxyProtocol.Tuic -> {
                val q = mutableListOf<String>()
                if (sni.isNotEmpty()) q += "sni=" + escapeData(sni)
                if (congestionControl.isNotEmpty()) q += "congestion_control=" + escapeData(congestionControl)
                if (udpRelayMode.isNotEmpty()) q += "udp_relay_mode=" + escapeData(udpRelayMode)
                if (alpn.isNotEmpty()) q += "alpn=" + escapeData(alpn)
                if (insecure) q += "allow_insecure=1"
                val query = if (q.isNotEmpty()) "?" + q.joinToString("&") else ""
                "tuic://$uuid:${escapeData(password)}@$cleanHost:$port$query$tag"
            }
            ProxyProtocol.Socks5 -> {
                val auth = if (username.isNotEmpty()) "${escapeData(username)}:${escapeData(password)}@" else ""
                "socks5://$auth$cleanHost:$port$tag"
            }
            else -> {
                val auth = if (username.isNotEmpty()) "${escapeData(username)}:${escapeData(password)}@" else ""
                "http://$auth$cleanHost:$port$tag"
            }
        }
    }

    /** What makes two subscription entries the same server (used for de-duplication and stable IDs). */
    fun fingerprintKey(): String {
        val h = cleanHost.trim().lowercase()
        return when (protocol) {
            ProxyProtocol.Vless -> "vless://${uuid.trim().lowercase()}@$h:$port?type=${transportType.trim().lowercase()}&security=${security.trim().lowercase()}&sni=${sni.trim().lowercase()}&pbk=${publicKey.trim()}&sid=${shortId.trim()}&fp=${fingerprint.trim().lowercase()}&path=${wsPath.trim()}&serviceName=${grpcServiceName.trim()}"
            ProxyProtocol.Trojan -> "trojan://${password.trim()}@$h:$port?type=${transportType.trim().lowercase()}&sni=${sni.trim().lowercase()}&path=${wsPath.trim()}&serviceName=${grpcServiceName.trim()}"
            ProxyProtocol.Shadowsocks -> "ss://${security.trim().lowercase()}:${password.trim()}@$h:$port"
            ProxyProtocol.Vmess -> "vmess://${uuid.trim().lowercase()}@$h:$port?net=${transportType.trim().lowercase()}&type=${wsPath.trim()}&tls=${security.trim().lowercase()}&sni=${sni.trim().lowercase()}"
            ProxyProtocol.Hysteria2 -> "hysteria2://${password.trim()}@$h:$port?ports=${serverPorts.trim()}&sni=${sni.trim().lowercase()}&obfs=${obfsPassword.trim()}"
            ProxyProtocol.Tuic -> "tuic://${uuid.trim().lowercase()}:${password.trim()}@$h:$port?sni=${sni.trim().lowercase()}"
            else -> "$protocol://${username.trim().lowercase()}@$h:$port"
        }
    }

    fun ensureDeterministicId() {
        if (isFromSubscription) id = deterministicGuid(fingerprintKey())
    }

    companion object {
        private val DIVIDER_MARKS = listOf("❗", "❕", "‼", "⚠", "ℹ", "📢", "📌", "🔻", "🔽", "⬇", "⬆", "➖", "—", "–", "═", "━", "─", "=", "#", "•", "*")
        private val DIVIDER_WORDS = listOf("ниже", "выше", "below", "above", "раздел", "section")

        /** MD5 of the text laid out like .NET's new Guid(bytes), so IDs match the Windows version. */
        fun deterministicGuid(input: String): String {
            val b = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            fun hex(vararg idx: Int) = idx.joinToString("") { "%02x".format(b[it].toInt() and 0xFF) }
            return "${hex(3, 2, 1, 0)}-${hex(5, 4)}-${hex(7, 6)}-${hex(8, 9)}-${hex(10, 11, 12, 13, 14, 15)}"
        }
    }
}
