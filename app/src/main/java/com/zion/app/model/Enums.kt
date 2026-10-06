package com.zion.app.model

import kotlinx.serialization.Serializable

enum class AppScreen { Dashboard, ServerList, ServerEdit, Settings, Subscriptions, Exclusions, ExcludedSites }

@Serializable
enum class DnsProvider { Auto, Cloudflare, Google, ControlD, NextDns, AdGuard, OpenDNS, CleanBrowsing }

@Serializable
enum class ProxyProtocol { Http, Https, Socks5, Vless, Trojan, Shadowsocks, Vmess, Hysteria2, Tuic }

/** What the last server check found (not saved: it describes this run only). */
enum class ProxyStatus {
    /** Not checked yet, or the check could not run. */
    Unknown,

    /** A real page loaded through the server. */
    Online,

    /** The server passed no traffic. */
    Offline
}
