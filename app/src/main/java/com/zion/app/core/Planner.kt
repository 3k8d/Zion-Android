package com.zion.app.core

import com.zion.app.model.ProxyItem
import com.zion.app.model.ProxyStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Decides which servers to try, and in what order, when the current one stops responding. */
object FailoverPlanner {
    /**
     * Same country as the failed server first (so the exit location changes as little as possible),
     * then everything else. Inside each group: favourites first, then servers not known to be offline,
     * fastest check first.
     */
    fun orderCandidates(current: ProxyItem?, all: Iterable<ProxyItem>, max: Int = 5): List<ProxyItem> {
        val code = current?.countryCode ?: ""
        val countryKnown = code.isNotEmpty() && code != "un" && code != "auto"
        return all
            .filter { (current == null || it.id != current.id) && !it.isDivider }
            .sortedWith(
                compareBy<ProxyItem>(
                    { if (countryKnown && it.countryCode.equals(code, true)) 0 else 1 },
                    { if (it.isFavorite) 0 else 1 },
                    { if (it.status == ProxyStatus.Offline) 1 else 0 },
                    { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE },
                )
            )
            .take(max)
    }
}

/**
 * Counts only the bytes that went through the VPN server, from snapshots of the core's /connections list.
 * Bytes of connections that closed between snapshots are attributed by the evidence at hand, so
 * VPN + direct always adds up to the core's own total. Same algorithm as Windows.
 */
class ProxiedTrafficCounter(private val proxyTag: String = "proxy-out") {
    private var seen = HashMap<String, Pair<Long, Boolean>>()
    private var lastGlobal = 0L

    var proxiedBytes = 0L
        private set
    var directBytes = 0L
        private set

    fun reset() {
        seen = HashMap()
        lastGlobal = 0
        proxiedBytes = 0
        directBytes = 0
    }

    /** Feeds one /connections snapshot. Returns false (and changes nothing) if it cannot be read. */
    fun apply(connectionsJson: String): Boolean {
        return try {
            val root = Json.parseToJsonElement(connectionsJson) as? JsonObject ?: return false
            val global = readLong(root, "uploadTotal") + readLong(root, "downloadTotal")
            if (global < lastGlobal) reset() // the core was restarted: its totals started over

            var tickProxied = 0L
            var tickDirect = 0L
            val seenNow = HashMap<String, Pair<Long, Boolean>>()

            (root["connections"] as? JsonArray)?.forEach { el ->
                val conn = el as? JsonObject ?: return@forEach
                val id = (conn["id"] as? JsonPrimitive)?.contentOrNull
                if (id.isNullOrEmpty()) return@forEach
                val bytes = readLong(conn, "upload") + readLong(conn, "download")
                val proxied = usesProxy(conn)
                val before = seen[id]?.first ?: 0L
                val grown = maxOf(0L, bytes - before)
                seenNow[id] = bytes to proxied
                if (proxied) tickProxied += grown else tickDirect += grown
            }

            val unexplained = maxOf(0L, global - (proxiedBytes + directBytes + tickProxied + tickDirect))
            var closedProxied = 0
            var closedDirect = 0
            for ((k, v) in seen) {
                if (seenNow.containsKey(k)) continue
                if (v.second) closedProxied++ else closedDirect++
            }

            var unexplainedProxied = unexplained
            if (closedProxied > 0 && closedDirect == 0) unexplainedProxied = unexplained
            else if (closedDirect > 0 && closedProxied == 0) unexplainedProxied = 0
            else if (tickProxied + tickDirect > 0) unexplainedProxied = (unexplained * (tickProxied.toDouble() / (tickProxied + tickDirect))).toLong()
            else if (proxiedBytes + directBytes > 0) unexplainedProxied = (unexplained * (proxiedBytes.toDouble() / (proxiedBytes + directBytes))).toLong()

            proxiedBytes += tickProxied + unexplainedProxied
            directBytes += tickDirect + (unexplained - unexplainedProxied)
            seen = seenNow
            lastGlobal = global
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun usesProxy(conn: JsonObject): Boolean =
        (conn["chains"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == proxyTag } == true

    private fun readLong(obj: JsonObject, name: String): Long = (obj[name] as? JsonPrimitive)?.longOrNull ?: 0L
}
