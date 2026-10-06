package com.zion.app.core

import com.zion.app.model.AppConfig
import com.zion.app.model.ProxyItem
import com.zion.app.model.ProxyStatus
import com.zion.app.model.SubscriptionEntry

/** Outcome of one subscription download, for the status line and the log. */
data class SubscriptionUpdateResult(val ok: Boolean, val added: Int, val removed: Int, val total: Int, val error: String) {
    companion object {
        fun failed(error: String) = SubscriptionUpdateResult(false, 0, 0, 0, error)
    }
}

object SubscriptionService {
    const val AUTO_UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000

    private fun normalize(url: String?) = (url ?: "").trim().trimEnd('/')

    /** Two links point to the same subscription (case, spaces and a trailing slash do not matter). */
    fun sameUrl(a: String?, b: String?) = normalize(a).equals(normalize(b), ignoreCase = true)

    fun find(config: AppConfig, url: String): SubscriptionEntry? = config.subscriptions.firstOrNull { sameUrl(it.url, url) }

    /** A server belongs to a subscription when it carries its link (very old ones without a link are adopted). */
    fun belongsTo(p: ProxyItem, url: String) = p.isFromSubscription && (p.subscriptionUrl.isBlank() || sameUrl(p.subscriptionUrl, url))

    /**
     * True when automatic updates are on and this subscription's last successful download is a day old
     * (or never happened). A failed attempt does not move that date, so it is retried on the next check.
     */
    fun isAutoUpdateDue(config: AppConfig, entry: SubscriptionEntry, now: Long): Boolean {
        if (!config.autoUpdateSubscription || entry.url.isBlank()) return false
        val last = entry.lastUpdate ?: return true
        return now - last >= AUTO_UPDATE_INTERVAL_MS || last > now // clock was moved back
    }

    /**
     * Applies one download to the list. A link that is not known yet is added as a new subscription;
     * existing subscriptions and manual servers are left alone. Runs on the UI thread (mutates the list).
     */
    fun apply(url: String, fetched: SubscriptionFetchResult, proxies: MutableList<ProxyItem>, config: AppConfig): SubscriptionUpdateResult {
        var entry = find(config, url)
        val isNew = entry == null
        val now = System.currentTimeMillis()

        if (fetched.items.isEmpty()) {
            val err = fetched.error.ifBlank { "Не удалось загрузить" }
            entry?.let {
                // A link that never worked is not added to the list
                it.lastAttempt = now
                it.lastError = err
            }
            return SubscriptionUpdateResult.failed(err)
        }

        if (entry == null) {
            entry = SubscriptionEntry(url = url)
            config.subscriptions += entry
        }
        entry.lastAttempt = now
        entry.lastUpdate = now
        entry.lastError = ""
        if (fetched.title.isNotBlank()) entry.title = fetched.title
        entry.upload = fetched.upload
        entry.download = fetched.download
        entry.total = fetched.total
        entry.expire = fetched.expire

        val (added, removed) = mergeSubscriptionItems(proxies, fetched.items, config, entry.url)
        val total = proxies.count { belongsTo(it, entry.url) }
        return SubscriptionUpdateResult(true, if (isNew) total else added, removed, total, "")
    }

    /**
     * Forgets a subscription and removes its servers. [keep] (the server in use) stays and becomes an
     * ordinary manual server, so a running connection is not cut. Returns how many servers were removed.
     */
    fun removeSubscription(entry: SubscriptionEntry, proxies: MutableList<ProxyItem>, config: AppConfig, keep: ProxyItem?): Int {
        var removed = 0
        for (p in proxies.filter { it.isFromSubscription && sameUrl(it.subscriptionUrl, entry.url) }) {
            if (p === keep) {
                p.isFromSubscription = false
                p.subscriptionUrl = ""
                continue
            }
            proxies.remove(p)
            removed++
        }
        config.subscriptions.removeAll { it.id == entry.id }
        return removed
    }

    /**
     * Merges a fresh download of ONE subscription into the list. Only that subscription's servers are
     * matched, updated or removed; manual servers and other subscriptions are never touched, and a fresh
     * server that already exists elsewhere in the list is not added twice.
     * Matching inside the subscription: same ID or connection, then same name (the provider changed
     * address or keys), then same location and protocol.
     */
    fun mergeSubscriptionItems(proxies: MutableList<ProxyItem>, freshItems: List<ProxyItem>, config: AppConfig, url: String = ""): Pair<Int, Int> {
        for (fresh in freshItems) {
            fresh.isFromSubscription = true
            fresh.subscriptionUrl = url
            fresh.ensureDeterministicId()
            fresh.autoEnrichCountryIfMissing()
        }

        val own = proxies.filter { belongsTo(it, url) }
        val foreign = proxies.filter { !belongsTo(it, url) }

        val ownById = own.filter { it.id.isNotEmpty() }.groupBy { it.id }.mapValues { it.value.first() }
        val ownByFingerprint = own.groupBy { it.fingerprintKey() }.mapValues { it.value.first() }
        val ownByName = own.filter { it.name.isNotBlank() }.groupBy { it.name.trim().lowercase() }.mapValues { it.value.first() }
        val ownByLocation = own.filter { it.locationShort.isNotBlank() && it.locationShort != "Локация не определена" }
            .groupBy { "${it.locationShort.trim().lowercase()}|${it.protocol}" }.mapValues { it.value.first() }

        val foreignIds = foreign.map { it.id }.toHashSet()
        val foreignFingerprints = foreign.map { it.fingerprintKey() }.toHashSet()

        val claimed = HashSet<ProxyItem>()  // an existing server can be matched only once
        val keptIds = HashSet<String>()
        val updates = mutableListOf<Pair<ProxyItem, ProxyItem>>()
        val additions = mutableListOf<ProxyItem>()

        for (fresh in freshItems) {
            val existing = ownById[fresh.id]?.takeIf { it !in claimed }
                ?: ownByFingerprint[fresh.fingerprintKey()]?.takeIf { it !in claimed }
                ?: (if (fresh.name.isNotBlank()) ownByName[fresh.name.trim().lowercase()]?.takeIf { it !in claimed } else null)
                ?: ownByLocation["${fresh.locationShort.trim().lowercase()}|${fresh.protocol}"]?.takeIf { it !in claimed }

            if (existing != null) {
                claimed += existing
                keptIds += existing.id
                updates += existing to fresh
                continue
            }
            // Already in the list through another subscription or added by hand: keep that one
            if (fresh.id in foreignIds || fresh.fingerprintKey() in foreignFingerprints) continue
            if (!keptIds.add(fresh.id)) continue // the same server twice in one download
            additions += fresh
        }

        // 1. Servers the provider no longer lists
        var removed = 0
        for (gone in own.filter { it !in claimed }) {
            proxies.remove(gone)
            removed++
        }

        // 2. Update matched servers in place (keeps favourites, check results, selection)
        for ((existing, fresh) in updates) {
            // A server that moved: what the last check found (and the address it pinned) no longer applies
            if (!existing.cleanHost.equals(fresh.cleanHost, true) || existing.port != fresh.port) {
                existing.status = ProxyStatus.Unknown
                existing.pingMs = -1
                existing.workingAddress = null
            }
            existing.name = fresh.name
            existing.host = fresh.host
            existing.port = fresh.port
            existing.protocol = fresh.protocol
            existing.uuid = fresh.uuid
            existing.transportType = fresh.transportType
            existing.security = fresh.security
            existing.fingerprint = fresh.fingerprint
            existing.sni = fresh.sni
            existing.publicKey = fresh.publicKey
            existing.shortId = fresh.shortId
            existing.grpcServiceName = fresh.grpcServiceName
            existing.wsPath = fresh.wsPath
            existing.wsHost = fresh.wsHost
            existing.username = fresh.username
            existing.password = fresh.password
            existing.alterId = fresh.alterId
            existing.flow = fresh.flow
            existing.spiderX = fresh.spiderX
            existing.alpn = fresh.alpn
            existing.rawLink = fresh.rawLink
            existing.obfsPassword = fresh.obfsPassword
            existing.serverPorts = fresh.serverPorts
            existing.insecure = fresh.insecure
            existing.congestionControl = fresh.congestionControl
            existing.udpRelayMode = fresh.udpRelayMode
            existing.isFromSubscription = true
            existing.subscriptionUrl = url
            if (fresh.country.isNotBlank()) existing.country = fresh.country
        }

        // 3. New servers
        proxies.addAll(additions)

        // 4. Keep the saved selection valid
        val sel = config.selectedProxyId
        if (sel != null && proxies.none { it.id == sel }) config.selectedProxyId = proxies.firstOrNull()?.id
        else if (sel == null && proxies.isNotEmpty()) config.selectedProxyId = proxies[0].id

        return additions.size to removed
    }
}
