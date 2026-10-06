package com.zion.app.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** One subscription link and what is known about it. Its servers carry the same URL in ProxyItem.subscriptionUrl. */
@Serializable
class SubscriptionEntry(
    var id: String = UUID.randomUUID().toString(),
    var url: String = "",
    /** Name sent by the provider (profile-title), empty if none. */
    var title: String = "",
    var addedAt: Long = System.currentTimeMillis(),
    /** Last successful download (epoch ms). */
    var lastUpdate: Long? = null,
    /** Last attempt, successful or not. */
    var lastAttempt: Long? = null,
    /** Why the last attempt failed; empty when it succeeded. */
    var lastError: String = "",
    // Plan details from the provider's subscription-userinfo header (bytes; 0 = unknown/unlimited)
    var upload: Long = 0,
    var download: Long = 0,
    var total: Long = 0,
    var expire: Long? = null,
)

/** An app whose traffic always goes around the VPN (Android: matched by its package). */
@Serializable
class ExcludedApp(
    /** Package name, e.g. "com.valvesoftware.android.steam.community". This is what the VPN excludes. */
    var packageName: String = "",
    /** Readable name from the launcher, e.g. "Steam". */
    var displayName: String = "",
)

@Serializable
class AppConfig(
    var schemaVersion: Int = 3,
    var proxies: MutableList<ProxyItem> = mutableListOf(),
    var selectedProxyId: String? = null,
    var autoConnectOnStartup: Boolean = false,
    var bypassTorrents: Boolean = false,
    var bypassDomesticRu: Boolean = false,
    /** All subscription links. Any number; each owns the servers that carry its URL. */
    var subscriptions: MutableList<SubscriptionEntry> = mutableListOf(),
    var autoUpdateSubscription: Boolean = false,
    /** Apps whose traffic always bypasses the VPN. */
    var directApps: MutableList<ExcludedApp> = mutableListOf(),
    /** Sites (with their subdomains) that always bypass the VPN, e.g. "kinopoisk.ru". */
    var directSites: MutableList<String> = mutableListOf(),
    var selectedDns: DnsProvider = DnsProvider.Auto,
    var autoServerFailover: Boolean = false,
    var blockQuic: Boolean = false,
    var blockWebRtc: Boolean = false,
    var blockTrackers: Boolean = false,
    var killSwitch: Boolean = false,
)
