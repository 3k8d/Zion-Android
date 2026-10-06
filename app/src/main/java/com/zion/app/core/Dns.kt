package com.zion.app.core

import com.zion.app.model.DnsProvider
import java.io.ByteArrayOutputStream
import java.net.InetAddress

class DnsEntry(
    val provider: DnsProvider,
    val displayName: String,
    val primaryIp: String,
    val secondaryIp: String,
    val dohHost: String,
    val dohPath: String = "/dns-query",
) {
    val singBoxTag: String get() = "remote-dns-${provider.name.lowercase()}"
}

/** One DNS-over-HTTPS server, addressed by IP (so checking it needs no DNS at all). */
data class DohTarget(val name: String, val ip: String, val host: String, val path: String)

data class DnsBenchmarkResult(val name: String, val serverIp: String, val path: String, val latencyMs: Int, val success: Boolean)

object DnsConstants {
    val entries: Map<DnsProvider, DnsEntry> = linkedMapOf(
        DnsProvider.Cloudflare to DnsEntry(DnsProvider.Cloudflare, "Cloudflare", "1.1.1.1", "1.0.0.1", "cloudflare-dns.com"),
        DnsProvider.Google to DnsEntry(DnsProvider.Google, "Google", "8.8.8.8", "8.8.4.4", "dns.google"),
        DnsProvider.ControlD to DnsEntry(DnsProvider.ControlD, "Control D", "76.76.2.0", "76.76.10.0", "freedns.controld.com", "/p0"),
        DnsProvider.NextDns to DnsEntry(DnsProvider.NextDns, "NextDNS", "45.90.28.0", "45.90.30.0", "dns.nextdns.io"),
        DnsProvider.AdGuard to DnsEntry(DnsProvider.AdGuard, "AdGuard", "94.140.14.14", "94.140.15.15", "dns.adguard-dns.com"),
        DnsProvider.OpenDNS to DnsEntry(DnsProvider.OpenDNS, "OpenDNS", "208.67.222.222", "208.67.220.220", "doh.opendns.com"),
        DnsProvider.CleanBrowsing to DnsEntry(DnsProvider.CleanBrowsing, "CleanBrowsing", "185.228.168.9", "185.228.169.9", "doh.cleanbrowsing.org", "/doh/security-filter/"),
    )

    fun entry(provider: DnsProvider): DnsEntry = entries[provider] ?: entries.getValue(DnsProvider.Cloudflare)

    fun allReal(): List<DnsEntry> = entries.values.toList()

    /** The provider that owns this address (primary or backup), or null. */
    fun findByIp(ip: String): DnsEntry? = entries.values.firstOrNull { it.primaryIp == ip || it.secondaryIp == ip }

    /** What the tunnel config will put first for these inputs. */
    fun resolvePrimary(provider: DnsProvider, autoOrder: List<DnsBenchmarkResult>?): DohTarget {
        if (provider == DnsProvider.Auto && !autoOrder.isNullOrEmpty()) {
            val first = autoOrder[0]
            val e = findByIp(first.serverIp)
            return DohTarget(e?.displayName ?: first.name, first.serverIp, e?.dohHost ?: "", e?.dohPath ?: first.path)
        }
        // A fixed choice uses its primary address; Auto without a measurement falls back to the providers' order (Cloudflare first)
        val e = entry(if (provider == DnsProvider.Auto) DnsProvider.Cloudflare else provider)
        return DohTarget(e.displayName, e.primaryIp, e.dohHost, e.dohPath)
    }

    /** Every server Zion knows, primary and backup addresses, in the providers' order. */
    fun allTargets(): List<DohTarget> = allReal().flatMap { e ->
        listOfNotNull(DohTarget(e.displayName, e.primaryIp, e.dohHost, e.dohPath),
            e.secondaryIp.takeIf { it.isNotBlank() }?.let { DohTarget(e.displayName, it, e.dohHost, e.dohPath) })
    }

    /**
     * Where to look when the current DNS server stops answering: in Auto mode any other server,
     * otherwise only the other address of the provider the user chose (their choice is respected).
     */
    fun failoverCandidates(mode: DnsProvider, current: DohTarget): List<DohTarget> {
        var all = allTargets().filter { it.ip != current.ip }
        if (mode != DnsProvider.Auto) {
            val chosen = entry(mode).displayName
            all = all.filter { it.name == chosen }
        }
        return all
    }
}

/** Minimal DNS wire format: one A question, and the A records of the answer. */
object DnsMessage {
    fun buildQuery(host: String, id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8 and 0xFF)
        out.write(id and 0xFF)
        out.write(byteArrayOf(0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)) // RD, 1 question
        for (label in host.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.isNotEmpty() && bytes.size <= 63) { "Invalid host name" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01)) // type A, class IN
        return out.toByteArray()
    }

    /** Collects the A records of a response. Returns nothing for a foreign, failed or malformed reply. */
    fun parseAddresses(r: ByteArray, expectedId: Int): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        fun u(i: Int) = r[i].toInt() and 0xFF
        if (r.size < 12) return result
        if ((u(0) shl 8 or u(1)) != expectedId) return result
        if (u(2) and 0x80 == 0) return result  // not a response
        if (u(3) and 0x0F != 0) return result  // error code (NXDOMAIN, SERVFAIL...)
        val questions = u(4) shl 8 or u(5)
        val answers = u(6) shl 8 or u(7)
        var pos = 12
        fun skipName(): Boolean {
            while (pos < r.size) {
                val len = u(pos)
                if (len == 0) { pos += 1; return true }
                if (len and 0xC0 == 0xC0) { pos += 2; return pos <= r.size }
                pos += 1 + len
            }
            return false
        }
        repeat(questions) {
            if (!skipName()) return result
            pos += 4
        }
        repeat(answers) {
            if (!skipName() || pos + 10 > r.size) return result
            val type = u(pos) shl 8 or u(pos + 1)
            val length = u(pos + 8) shl 8 or u(pos + 9)
            pos += 10
            if (pos + length > r.size) return result
            if (type == 1 && length == 4) result += InetAddress.getByAddress(r.copyOfRange(pos, pos + 4))
            pos += length
        }
        return result
    }
}
