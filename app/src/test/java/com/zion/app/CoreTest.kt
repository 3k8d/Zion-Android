package com.zion.app

import com.zion.app.core.ClashApi
import com.zion.app.core.ConfigBuilder
import com.zion.app.core.ControlEndpoint
import com.zion.app.core.Countries
import com.zion.app.core.DirectSites
import com.zion.app.core.DnsBenchmarkResult
import com.zion.app.core.DnsConstants
import com.zion.app.core.DnsMessage
import com.zion.app.core.FailoverPlanner
import com.zion.app.core.Format
import com.zion.app.core.ProxiedTrafficCounter
import com.zion.app.core.ProxyParser
import com.zion.app.core.SubscriptionFetch
import com.zion.app.core.SubscriptionFetchResult
import com.zion.app.core.SubscriptionService
import com.zion.app.core.TunnelSettings
import com.zion.app.core.base64
import com.zion.app.core.escapeData
import com.zion.app.core.unescapeData
import com.zion.app.model.AppConfig
import com.zion.app.model.DnsProvider
import com.zion.app.model.ProxyItem
import com.zion.app.model.ProxyProtocol
import com.zion.app.model.ProxyStatus
import com.zion.app.model.SubscriptionEntry
import com.zion.app.vm.ServerListState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private val control = ControlEndpoint(19090, "secret")

    private fun JsonObject.obj(k: String) = this[k]!!.jsonObject
    private fun JsonObject.arr(k: String) = this[k]!!.jsonArray
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun rules(cfg: JsonObject) = cfg.obj("route").arr("rules").map { it.jsonObject }
    private fun dnsRules(cfg: JsonObject) = cfg.obj("dns").arr("rules").map { it.jsonObject }

    // ------------------------------------------------------------------ text helpers

    @Test fun escapeAndUnescapeMatchDotNet() {
        assertEquals("a%20b%2Fc%3F%D0%AF", escapeData("a b/c?Я"))
        assertEquals("a b/c?Я", unescapeData("a%20b%2Fc%3F%D0%AF"))
        assertEquals("a+b", unescapeData("a+b"))
        assertEquals("100%", unescapeData("100%"))
    }

    // ------------------------------------------------------------------ links

    @Test fun parsesVlessRealityLink() {
        val p = ProxyParser.parseSingle("vless://11111111-2222-3333-4444-555555555555@de1.example.net:443?type=tcp&security=reality&pbk=PUBKEY&sid=ab12&fp=firefox&sni=yahoo.com&flow=xtls-rprx-vision#%F0%9F%87%A9%F0%9F%87%AA%20Germany")!!
        assertEquals(ProxyProtocol.Vless, p.protocol)
        assertEquals("de1.example.net", p.host)
        assertEquals(443, p.port)
        assertEquals("reality", p.security)
        assertEquals("PUBKEY", p.publicKey)
        assertEquals("ab12", p.shortId)
        assertEquals("firefox", p.fingerprint)
        assertEquals("yahoo.com", p.sni)
        assertEquals("xtls-rprx-vision", p.flow)
        assertEquals("🇩🇪 Germany", p.name)
        assertEquals("Germany", p.cleanName)
        assertEquals("de", p.countryCode)
        assertEquals("VLESS / TCP", p.protocolBadge)
    }

    @Test fun parsesVlessGrpcPathAsServiceName() {
        val p = ProxyParser.parseSingle("vless://id@h.example.com:8443?type=grpc&security=tls&path=svc#x")!!
        assertEquals("grpc", p.transportType)
        assertEquals("svc", p.grpcServiceName)
        assertEquals("VLESS / gRPC", p.protocolBadge)
    }

    @Test fun parsesIpv6Host() {
        val p = ProxyParser.parseSingle("trojan://pw@[2001:db8::1]:443?sni=a.example#t")!!
        assertEquals("2001:db8::1", p.host)
        assertEquals(443, p.port)
        assertEquals("pw", p.password)
    }

    @Test fun parsesTrojanWs() {
        val p = ProxyParser.parseSingle("trojan://p%40ss@t.example.org:443?type=ws&path=%2Fws&host=cdn.example.org&sni=t.example.org#Trojan")!!
        assertEquals(ProxyProtocol.Trojan, p.protocol)
        assertEquals("p@ss", p.password)
        assertEquals("/ws", p.wsPath)
        assertEquals("cdn.example.org", p.wsHost)
        assertEquals("Trojan / WS", p.protocolBadge)
    }

    @Test fun parsesShadowsocksBothForms() {
        val a = ProxyParser.parseSingle("ss://" + base64("aes-256-gcm:secret".toByteArray()) + "@ss.example.com:8388#SS")!!
        assertEquals("aes-256-gcm", a.security)
        assertEquals("secret", a.password)
        assertEquals(8388, a.port)
        val b = ProxyParser.parseSingle("ss://" + base64("chacha20-ietf-poly1305:pw@1.2.3.4:443".toByteArray()) + "#Whole")!!
        assertEquals("1.2.3.4", b.host)
        assertEquals("chacha20-ietf-poly1305", b.security)
        assertEquals("Whole", b.name)
    }

    @Test fun parsesVmessJson() {
        val json = """{"v":"2","ps":"VM","add":"vm.example.com","port":"443","id":"uuid-1","aid":"0","net":"ws","path":"/p","host":"h.example.com","tls":"tls","sni":""}"""
        val p = ProxyParser.parseSingle("vmess://" + base64(json.toByteArray()))!!
        assertEquals(ProxyProtocol.Vmess, p.protocol)
        assertEquals(443, p.port)
        assertEquals("tls", p.security)
        assertEquals("h.example.com", p.sni)
        assertEquals("ws", p.transportType)
    }

    @Test fun parsesHysteria2WithPortHopping() {
        val p = ProxyParser.parseSingle("hysteria2://pass@hy.example.com:443,20000-30000/?sni=hy.example.com&obfs=salamander&obfs-password=ob&insecure=1#Hy")!!
        assertEquals(ProxyProtocol.Hysteria2, p.protocol)
        assertEquals(443, p.port)
        assertEquals("443,20000-30000", p.serverPorts)
        assertEquals("ob", p.obfsPassword)
        assertTrue(p.insecure)
        val hy2 = ProxyParser.parseSingle("hy2://x@h.example:8443#n")!!
        assertEquals(8443, hy2.port)
        assertEquals("", hy2.serverPorts)
    }

    @Test fun parsesTuic() {
        val p = ProxyParser.parseSingle("tuic://11111111-2222-3333-4444-555555555555:pw@tu.example.com:443?congestion_control=BBR&udp_relay_mode=native&alpn=h3&allow_insecure=1#T")!!
        assertEquals(ProxyProtocol.Tuic, p.protocol)
        assertEquals("bbr", p.congestionControl)
        assertEquals("native", p.udpRelayMode)
        assertTrue(p.insecure)
        assertNull("TUIC needs both UUID and password", ProxyParser.parseSingle("tuic://onlyuuid@h.example:443"))
    }

    @Test fun parsesPlainProxyFormats() {
        val a = ProxyParser.parseSingle("socks5://user:pw@10.0.0.5:1080#Home")!!
        assertEquals(ProxyProtocol.Socks5, a.protocol)
        assertEquals("user", a.username)
        assertEquals("Home", a.name)
        val b = ProxyParser.parseSingle("203.0.113.10:3128:login:secret")!!
        assertEquals(ProxyProtocol.Http, b.protocol)
        assertEquals("login", b.username)
        assertEquals("secret", b.password)
        assertNull(ProxyParser.parseSingle("not a proxy"))
        assertNull(ProxyParser.parseSingle("host:99999"))
    }

    @Test fun shareLinkRoundTrip() {
        val links = listOf(
            "vless://id@a.example.com:443?type=tcp&security=reality&pbk=K&fp=chrome&sni=s.example#N",
            "trojan://pw@b.example.com:443?type=ws&sni=b.example.com&path=%2Fw#T",
            "hysteria2://pw@c.example.com:443/?sni=c.example.com&obfs=salamander&obfs-password=o&mport=20000-30000#H",
            "tuic://u:pw@d.example.com:443?sni=d.example.com&congestion_control=bbr#U",
        )
        for (l in links) {
            val p = ProxyParser.parseSingle(l)!!
            val again = ProxyParser.parseSingle(p.toShareableUrl())!!
            assertEquals(p.fingerprintKey(), again.fingerprintKey())
            assertEquals(p.name, again.name)
        }
        val ss = ProxyItem(name = "S", host = "e.example.com", port = 8388, protocol = ProxyProtocol.Shadowsocks, password = "pw", security = "aes-128-gcm")
        val ssBack = ProxyParser.parseSingle(ss.toShareableUrl())!!
        assertEquals("aes-128-gcm", ssBack.security)
        assertEquals("pw", ssBack.password)
    }

    @Test fun deterministicIdsForSubscriptionServers() {
        val a = ProxyParser.parseSingle("vless://id@a.example.com:443?security=tls#A")!!.apply { isFromSubscription = true; ensureDeterministicId() }
        val b = ProxyParser.parseSingle("vless://id@a.example.com:443?security=tls#Renamed")!!.apply { isFromSubscription = true; ensureDeterministicId() }
        assertEquals("Same connection, same ID", a.id, b.id)
        assertTrue(a.id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test fun bulkAndBase64Subscriptions() {
        val body = "vless://id@a.example.com:443#A\n# comment\ntrojan://pw@b.example.com:443#B\n"
        assertEquals(2, ProxyParser.parseBulk(body).size)
        assertEquals(2, ProxyParser.parseBulk(base64(body.toByteArray())).size)
        val singBox = """{"outbounds":[{"type":"vless","tag":"Happ-Node-1","server":"95.163.22.10","server_port":443,"uuid":"u",
            "tls":{"enabled":true,"server_name":"s","reality":{"enabled":true,"public_key":"pbk12345","short_id":"1"}}},
            {"type":"direct","tag":"direct"},{"type":"hysteria2","tag":"H","server":"h.example","server_port":443,"password":"pw",
            "obfs":{"type":"salamander","password":"o"},"server_ports":["20000:30000"],"tls":{"enabled":true,"insecure":true,"alpn":["h3"]}}]}"""
        val parsed = ProxyParser.parseBulk(singBox)
        assertEquals(2, parsed.size)
        assertEquals("reality", parsed[0].security)
        assertEquals("pbk12345", parsed[0].publicKey)
        assertEquals("Happ-Node-1", parsed[0].name)
        assertEquals("20000-30000", parsed[1].serverPorts)
        assertTrue(parsed[1].insecure)
        assertEquals("h3", parsed[1].alpn)
    }

    @Test fun subscriptionUrlDetection() {
        assertTrue(ProxyParser.isSubscriptionUrl("https://sub.example.com/api/v1/client/subscribe?token=abc"))
        assertTrue(ProxyParser.isSubscriptionUrl("happ://add/https://panel.example.com/sub/xyz"))
        assertFalse(ProxyParser.isSubscriptionUrl("http://user:pass@1.2.3.4:8080"))
        assertFalse(ProxyParser.isSubscriptionUrl("vless://id@h:443"))
        assertEquals("https://x.example/sub", ProxyParser.extractActualSubscriptionUrl("v2rayng://install-sub?url=https%3A%2F%2Fx.example%2Fsub&name=a"))
        assertEquals("https://y.example/s", ProxyParser.extractActualSubscriptionUrl("sub://" + base64("https://y.example/s".toByteArray())))
    }

    @Test fun subscriptionHeaders() {
        val info = ProxyParser.parseSubscriptionUserInfo("upload=100; download=200; total=1000; expire=1700000000")
        assertEquals(100L, info[0]); assertEquals(200L, info[1]); assertEquals(1000L, info[2]); assertEquals(1700000000000L, info[3])
        assertEquals(-1L, ProxyParser.parseSubscriptionUserInfo("expire=0")[3])
        assertEquals("Мой VPN", ProxyParser.decodeProfileTitle("base64:" + base64("Мой VPN".toByteArray())))
        assertEquals("Plain Name", ProxyParser.decodeProfileTitle(" Plain Name "))
        assertEquals("Мой план.txt", SubscriptionFetch.fileNameFrom("attachment; filename*=UTF-8''%D0%9C%D0%BE%D0%B9%20%D0%BF%D0%BB%D0%B0%D0%BD.txt"))
    }

    @Test fun localAddressesAreRefused() {
        for (h in listOf("localhost", "127.0.0.1", "10.1.2.3", "192.168.0.1", "172.20.0.1", "169.254.169.254", "router.local", "::1")) {
            assertTrue(h, SubscriptionFetch.isLocalHost(h))
        }
        assertFalse(SubscriptionFetch.isLocalHost("sub.example.com"))
        assertFalse(SubscriptionFetch.isLocalHost("8.8.8.8"))
        assertTrue(SubscriptionFetch.fetch("https://127.0.0.1/sub", "h", false).error.contains("Локальные"))
    }

    // ------------------------------------------------------------------ countries

    @Test fun countryResolution() {
        assertEquals("nl", Countries.resolveIsoCode("🇳🇱 Amsterdam"))
        assertEquals("de", Countries.resolveIsoCode("Франкфурт #2"))
        assertEquals("gb", Countries.resolveIsoCode(null, "uk", null))
        assertEquals("fi", Countries.resolveIsoCode("Server", null, "fi-hel1.example.com"))
        assertEquals("auto", Countries.resolveIsoCode("Авто выбор", null, null))
        assertEquals("Нидерланды", Countries.nameRu("nl"))
        assertEquals("USA, Frankfurt", Countries.normalizeLocation("United States of America, Frankfurt am Main"))
        val p = ProxyItem(name = "Node", host = "nl.example.com")
        p.autoEnrichCountryIfMissing()
        assertEquals("Нидерланды", p.country)
    }

    @Test fun dividersAreRecognised() {
        val d = ProxyItem(name = "❗️Белые списки ниже")
        assertTrue(d.isDivider)
        assertEquals("Белые списки ниже", d.dividerTitle)
        assertFalse(ProxyItem(name = "🇩🇪 Германия").isDivider)
        assertFalse(ProxyItem(name = "#1 Server").isDivider)
    }

    // ------------------------------------------------------------------ subscriptions merge

    private fun sub(name: String, host: String, url: String) =
        ProxyParser.parseSingle("vless://id-$host@$host:443?security=tls#$name")!!.apply { isFromSubscription = true; subscriptionUrl = url; ensureDeterministicId() }

    @Test fun mergeUpdatesInPlaceAndKeepsStars() {
        val url = "https://a.example/sub"
        val cfg = AppConfig()
        val a1 = sub("A1", "a1.example.com", url).apply { isFavorite = true; status = ProxyStatus.Online; pingMs = 40 }
        val manual = ProxyItem(name = "Mine", host = "m.example.com")
        val list = mutableListOf(a1, manual)
        val fresh = listOf(sub("A1 renamed", "a1.example.com", url), sub("A2", "a2.example.com", url))
        val (added, removed) = SubscriptionService.mergeSubscriptionItems(list, fresh, cfg, url)
        assertEquals(1, added); assertEquals(0, removed)
        assertTrue("Same object updated in place", list[0] === a1)
        assertEquals("A1 renamed", a1.name)
        assertTrue(a1.isFavorite)
        assertEquals(ProxyStatus.Online, a1.status)
        assertTrue("Manual server untouched", manual in list)
    }

    @Test fun mergeResetsCheckWhenServerMoves() {
        val url = "https://a.example/sub"
        val a = sub("A", "old.example.com", url).apply { status = ProxyStatus.Offline; workingAddress = "203.0.113.1" }
        val list = mutableListOf(a)
        SubscriptionService.mergeSubscriptionItems(list, listOf(sub("A", "new.example.com", url)), AppConfig(), url)
        assertEquals(ProxyStatus.Unknown, a.status)
        assertNull(a.workingAddress)
        assertEquals("new.example.com", a.host)
    }

    @Test fun mergeRemovesGoneAndSkipsDuplicatesOfOtherSubscriptions() {
        val urlA = "https://a.example/sub"
        val urlB = "https://b.example/sub"
        val a1 = sub("A1", "a1.example.com", urlA)
        val b1 = sub("B1", "shared.example.com", urlB)
        val list = mutableListOf(a1, b1)
        val (added, removed) = SubscriptionService.mergeSubscriptionItems(list, listOf(sub("Shared", "shared.example.com", urlA)), AppConfig(), urlA)
        assertEquals(1, removed)
        assertEquals("Already in another subscription: not added twice", 0, added)
        assertEquals(listOf(b1), list)
    }

    @Test fun applyAddsNewSubscriptionAndRecordsFailures() {
        val cfg = AppConfig()
        val list = mutableListOf<ProxyItem>()
        val url = "https://n.example/sub"
        val failed = SubscriptionService.apply(url, SubscriptionFetchResult(error = "Сервер подписки не отвечает"), list, cfg)
        assertFalse(failed.ok)
        assertTrue("A link that never worked is not added", cfg.subscriptions.isEmpty())
        val ok = SubscriptionService.apply(url, SubscriptionFetchResult(items = listOf(sub("X", "x.example.com", url)), title = "Prov", total = 100), list, cfg)
        assertTrue(ok.ok)
        assertEquals(1, cfg.subscriptions.size)
        assertEquals("Prov", cfg.subscriptions[0].title)
        assertEquals(1, ok.total)
        val again = SubscriptionService.apply(url, SubscriptionFetchResult(error = "boom"), list, cfg)
        assertFalse(again.ok)
        assertEquals("boom", cfg.subscriptions[0].lastError)
        assertEquals("Servers stay when an update fails", 1, list.size)
    }

    @Test fun autoUpdateIsDueOncePerDay() {
        val cfg = AppConfig(autoUpdateSubscription = true)
        val now = 1_000_000_000_000L
        val e = SubscriptionEntry(url = "https://x/sub", lastUpdate = now - 23 * 3600_000L)
        assertFalse(SubscriptionService.isAutoUpdateDue(cfg, e, now))
        e.lastUpdate = now - 25 * 3600_000L
        assertTrue(SubscriptionService.isAutoUpdateDue(cfg, e, now))
        e.lastUpdate = null
        assertTrue(SubscriptionService.isAutoUpdateDue(cfg, e, now))
        cfg.autoUpdateSubscription = false
        assertFalse(SubscriptionService.isAutoUpdateDue(cfg, e, now))
    }

    @Test fun removeSubscriptionKeepsServerInUse() {
        val url = "https://a.example/sub"
        val cfg = AppConfig()
        val entry = SubscriptionEntry(url = url).also { cfg.subscriptions += it }
        val inUse = sub("A1", "a1.example.com", url)
        val other = sub("A2", "a2.example.com", url)
        val list = mutableListOf(inUse, other)
        assertEquals(1, SubscriptionService.removeSubscription(entry, list, cfg, inUse))
        assertEquals(listOf(inUse), list)
        assertFalse(inUse.isFromSubscription)
        assertTrue(cfg.subscriptions.isEmpty())
    }

    @Test fun listSections() {
        val urlA = "https://a.example/sub"
        val urlB = "https://b.example/sub"
        val subs = listOf(SubscriptionEntry(url = urlA, title = "Provider A"), SubscriptionEntry(url = urlB))
        val a1 = sub("A1", "a1.example.com", urlA)
        val a2 = sub("A2", "a2.example.com", urlA).apply { isFavorite = true }
        val div = sub("❗️Белые списки ниже", "d.example.com", urlA)
        val b1 = sub("B1", "b1.example.com", urlB)
        val manual = ProxyItem(name = "Mine", host = "m.example.com")
        val sections = ServerListState.buildSections(listOf(a1, a2, div, b1, manual), subs)
        assertEquals(listOf("fav", "sub:" + subs[0].id, "sub:" + subs[1].id, "manual"), sections.map { it.key })
        assertEquals(listOf(a2), sections[0].members)
        assertEquals("Provider A", sections[1].title)
        assertEquals(listOf(a1, div), sections[1].members)
        assertEquals("b.example", sections[2].title)
        assertEquals("Добавлены вручную", sections[3].title)
    }

    // ------------------------------------------------------------------ tunnel config

    private val vless = ProxyParser.parseSingle("vless://id@srv.example.com:443?type=tcp&security=reality&pbk=K&sid=1&sni=yahoo.com#Srv")!!

    @Test fun tunnelConfigBasics() {
        val cfg = ConfigBuilder.generateTunnelConfig(vless, TunnelSettings(), control)
        val tun = cfg.arr("inbounds")[0].jsonObject
        assertEquals("tun", tun.str("type"))
        assertTrue(tun["auto_route"]!!.jsonPrimitive.boolean)
        assertNull("No exclusions by default", tun["exclude_package"])
        assertEquals("127.0.0.1:19090", cfg.obj("experimental").obj("clash_api").str("external_controller"))
        assertEquals("secret", cfg.obj("experimental").obj("clash_api").str("secret"))
        assertEquals("ipv4_only", cfg.obj("dns").str("strategy"))
        assertEquals("proxy-out", cfg.obj("route").str("final"))
        val r = rules(cfg)
        assertEquals("sniff", r[0].str("action"))
        assertEquals("hijack-dns", r[1].str("action"))
        assertTrue("IPv6 is rejected", r.any { it["ip_version"]?.jsonPrimitive?.int == 6 && it.str("action") == "reject" })
        assertTrue("The server's own name goes direct", r.any { it["domain"]?.jsonArray?.first()?.jsonPrimitive?.content == "srv.example.com" })
        val out = cfg.arr("outbounds")[0].jsonObject
        assertEquals("vless", out.str("type"))
        assertEquals("K", out.obj("tls").obj("reality").str("public_key"))
        assertEquals("15s", out.str("tcp_keep_alive"))
        assertEquals("direct-dns", cfg.obj("dns").arr("servers").last().jsonObject.str("tag"))
    }

    @Test fun togglesShapeTheConfig() {
        val off = ConfigBuilder.generateTunnelConfig(vless, TunnelSettings(), control).toString()
        assertFalse(off.contains("bittorrent"))
        assertFalse(off.contains("19302"))
        val s = TunnelSettings(bypassTorrents = true, bypassDomesticRu = true, blockQuic = true, blockWebRtc = true, blockTrackers = true,
            directApps = listOf("com.example.bank"), directSites = listOf("https://www.Kinopoisk.ru/film/1", "кинопоиск.рф"))
        val cfg = ConfigBuilder.generateTunnelConfig(vless, s, control)
        val r = rules(cfg)
        val str = cfg.toString()
        assertTrue(str.contains("bittorrent"))
        assertTrue(r.any { it["port"]?.jsonArray?.map { p -> p.jsonPrimitive.int } == listOf(443, 80) && it.str("action") == "reject" })
        assertTrue(r.any { it["port"]?.jsonArray?.map { p -> p.jsonPrimitive.int } == listOf(19302, 3478, 5349) })
        val excluded = cfg.arr("inbounds")[0].jsonObject.arr("exclude_package").map { it.jsonPrimitive.content }
        assertTrue("User's app", "com.example.bank" in excluded)
        assertTrue("Torrent clients with the bypass", "org.proninyaroslav.libretorrent" in excluded)
        // Order: user's sites first, then trackers, then the RU bypass (mc.yandex.ru must be blocked, not sent direct)
        val sitesIdx = r.indexOfFirst { it["domain_suffix"]?.jsonArray?.any { d -> d.jsonPrimitive.content == "kinopoisk.ru" } == true }
        val trackersIdx = r.indexOfFirst { it["domain_suffix"]?.jsonArray?.any { d -> d.jsonPrimitive.content == "mc.yandex.ru" } == true }
        val ruIdx = r.indexOfFirst { it["domain_suffix"]?.jsonArray?.any { d -> d.jsonPrimitive.content == ".ru" } == true }
        assertTrue(sitesIdx in 0 until trackersIdx)
        assertTrue(trackersIdx < ruIdx)
        assertTrue(dnsRules(cfg).any { it["domain_suffix"]?.jsonArray?.any { d -> d.jsonPrimitive.content == "xn--h1aaecngahu.xn--p1ai" } == true })
    }

    @Test fun pinnedAddressGoesDirectAndIsDialled() {
        val cfg = ConfigBuilder.generateTunnelConfig(vless, TunnelSettings(), control, serverAddress = "203.0.113.7")
        assertEquals("203.0.113.7", cfg.arr("outbounds")[0].jsonObject.str("server"))
        assertEquals("yahoo.com", cfg.arr("outbounds")[0].jsonObject.obj("tls").str("server_name"))
        assertTrue(rules(cfg).any { it["ip_cidr"]?.jsonArray?.first()?.jsonPrimitive?.content == "203.0.113.7/32" })
    }

    @Test fun dnsServersPerProvider() {
        val auto = ConfigBuilder.generateTunnelConfig(vless, TunnelSettings(dnsProvider = DnsProvider.Auto,
            autoDnsOrder = listOf(DnsBenchmarkResult("Google", "8.8.8.8", "/dns-query", 10, true), DnsBenchmarkResult("Cloudflare", "1.1.1.1", "/dns-query", 20, true))), control)
        val servers = auto.obj("dns").arr("servers").map { it.jsonObject }
        assertEquals("remote-dns", servers[0].str("tag"))
        assertEquals("8.8.8.8", servers[0].str("server"))
        assertEquals("dns.google", servers[0].obj("tls").str("server_name"))
        assertEquals("proxy-out", servers[0].str("detour"))
        val adguard = ConfigBuilder.generateTunnelConfig(vless, TunnelSettings(dnsProvider = DnsProvider.AdGuard), control)
        val ag = adguard.obj("dns").arr("servers").map { it.jsonObject }
        assertEquals(listOf("94.140.14.14", "94.140.15.15"), ag.take(2).map { it.str("server") })
        assertEquals("Google", DnsConstants.resolvePrimary(DnsProvider.Auto, listOf(DnsBenchmarkResult("Google", "8.8.8.8", "/dns-query", 1, true))).name)
        assertEquals("Cloudflare", DnsConstants.resolvePrimary(DnsProvider.Auto, null).name)
    }

    @Test fun dnsFailoverCandidates() {
        val current = DnsConstants.resolvePrimary(DnsProvider.AdGuard, null)
        val own = DnsConstants.failoverCandidates(DnsProvider.AdGuard, current)
        assertEquals(1, own.size)
        assertEquals("94.140.15.15", own[0].ip)
        assertEquals(13, DnsConstants.failoverCandidates(DnsProvider.Auto, current).size)
    }

    @Test fun quicOutbounds() {
        val hy = ProxyParser.parseSingle("hysteria2://pw@hy.example.com:443,20000-30000/?obfs-password=o&insecure=1#H")!!
        val out = ConfigBuilder.buildProxyOutbound(hy)
        assertNull("Port hopping replaces the single port", out["server_port"])
        assertEquals(listOf("443:443", "20000:30000"), out.arr("server_ports").map { it.jsonPrimitive.content })
        assertEquals("salamander", out.obj("obfs").str("type"))
        assertTrue(out.obj("tls")["insecure"]!!.jsonPrimitive.boolean)
        assertEquals("h3", out.obj("tls").arr("alpn")[0].jsonPrimitive.content)
        assertNull("No keep-alive on UDP", out["tcp_keep_alive"])
        assertNull("No uTLS on QUIC", out.obj("tls")["utls"])
        val tuic = ConfigBuilder.buildProxyOutbound(ProxyParser.parseSingle("tuic://u:p@t.example:443?congestion_control=cubic#T")!!)
        assertEquals("cubic", tuic.str("congestion_control"))
        assertEquals(listOf("20000:30000", "443:443"), ConfigBuilder.hysteriaPortRanges("20000-30000, 443, 70000, 5-1, abc"))
    }

    @Test fun otherOutbounds() {
        val tr = ConfigBuilder.buildProxyOutbound(ProxyParser.parseSingle("trojan://pw@t.example:443?type=grpc&serviceName=g#T")!!)
        assertEquals("grpc", tr.obj("transport").str("type"))
        assertEquals("g", tr.obj("transport").str("service_name"))
        assertEquals(listOf("h2", "http/1.1"), tr.obj("tls").arr("alpn").map { it.jsonPrimitive.content })
        val socks = ConfigBuilder.buildProxyOutbound(ProxyItem(host = "1.2.3.4", port = 1080, protocol = ProxyProtocol.Socks5, username = "u", password = "p"))
        assertEquals("socks", socks.str("type"))
        assertEquals("u", socks.str("username"))
        val ws = ConfigBuilder.buildProxyOutbound(ProxyParser.parseSingle("vless://id@v.example:443?type=ws&security=tls&path=%2Fp&host=cdn.example#W")!!)
        assertEquals("cdn.example", ws.obj("transport").obj("headers").str("Host"))
        assertEquals(listOf("http/1.1"), ws.obj("tls").arr("alpn").map { it.jsonPrimitive.content })
    }

    @Test fun checkConfigOneOutboundPerAddress() {
        val targets = listOf(vless to "203.0.113.7", vless to "203.0.113.8", ProxyItem(host = "9.9.9.9", protocol = ProxyProtocol.Socks5) to null)
        val cfg = ConfigBuilder.generateCheckConfig(targets, control)
        val outs = cfg.arr("outbounds").map { it.jsonObject }
        assertEquals(4, outs.size)
        assertEquals(listOf("s0", "s1", "s2", "direct"), outs.map { it.str("tag") })
        assertEquals("203.0.113.8", outs[1].str("server"))
        assertTrue(cfg.obj("route")["auto_detect_interface"]!!.jsonPrimitive.boolean)
        assertNull("No inbounds in the check", cfg["inbounds"])
        assertEquals(2, ConfigBuilder.parseBadOutbound("create service: initialize outbound[2]: invalid public_key"))
        assertNull(ConfigBuilder.parseBadOutbound("something else"))
        assertTrue(ConfigBuilder.isPortConflict("listen tcp 127.0.0.1:9090: bind: address already in use"))
    }

    @Test fun directAppsAreSanitised() {
        val r = ConfigBuilder.sanitizeDirectApps(listOf("com.a.b", " com.a.b ", "COM.A.B", "com.zion.app", "bad name", "", "org.x"), "com.zion.app")
        assertEquals(listOf("com.a.b", "org.x"), r)
    }

    // ------------------------------------------------------------------ sites

    @Test fun directSitesNormalise() {
        assertEquals("kinopoisk.ru", DirectSites.normalize("https://www.kinopoisk.ru/film/1").domain)
        assertEquals("vk.com", DirectSites.normalize("*.VK.com").domain)
        assertEquals("xn--h1aaecngahu.xn--p1ai", DirectSites.normalize("КИНОПОИСК.РФ").domain)
        assertEquals("кинопоиск.рф", DirectSites.display("xn--h1aaecngahu.xn--p1ai"))
        assertNull(DirectSites.normalize("1.2.3.4").domain)
        assertNull(DirectSites.normalize("localhost").domain)
        assertNull(DirectSites.normalize("").domain)
        assertEquals(listOf("a.com", "b.org"), DirectSites.sanitize(listOf("a.com", "A.com", "b.org", "bad")))
    }

    // ------------------------------------------------------------------ failover

    @Test fun failoverOrder() {
        val failed = ProxyItem(name = "🇩🇪 Old", host = "de0.example.com")
        val deFast = ProxyItem(name = "🇩🇪 Fast", host = "a").apply { pingMs = 30 }
        val deSlow = ProxyItem(name = "🇩🇪 Slow", host = "b").apply { pingMs = 90 }
        val deDead = ProxyItem(name = "🇩🇪 Dead", host = "c").apply { status = ProxyStatus.Offline }
        val nlFast = ProxyItem(name = "🇳🇱 Fast", host = "d").apply { pingMs = 20 }
        val nlFav = ProxyItem(name = "🇳🇱 Fav", host = "f").apply { isFavorite = true; pingMs = 200 }
        val us = ProxyItem(name = "🇺🇸 ?", host = "e")
        val order = FailoverPlanner.orderCandidates(failed, listOf(us, nlFast, deDead, deSlow, failed, deFast, nlFav), max = 10)
        assertEquals(listOf(deFast, deSlow, deDead, nlFav, nlFast, us), order)
        assertEquals(2, FailoverPlanner.orderCandidates(failed, order, max = 2).size)
    }

    // ------------------------------------------------------------------ DNS wire

    @Test fun dnsMessageRoundTrip() {
        val q = DnsMessage.buildQuery("example.com", 0xBEEF)
        assertEquals(0xBE.toByte(), q[0])
        val reply = q.copyOf(q.size + 2 * 16)
        reply[2] = 0x81.toByte(); reply[3] = 0x80.toByte(); reply[7] = 2
        var pos = q.size
        for (last in listOf(10, 11)) {
            val rr = byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 203.toByte(), 0, 113, last.toByte())
            rr.copyInto(reply, pos)
            pos += 16
        }
        val parsed = DnsMessage.parseAddresses(reply, 0xBEEF)
        assertEquals(listOf("203.0.113.10", "203.0.113.11"), parsed.map { it.hostAddress })
        assertTrue("Foreign ID is rejected", DnsMessage.parseAddresses(reply, 1).isEmpty())
        val nx = reply.copyOf().also { it[3] = 0x83.toByte() }
        assertTrue("NXDOMAIN gives nothing", DnsMessage.parseAddresses(nx, 0xBEEF).isEmpty())
        assertTrue(DnsMessage.parseAddresses(ByteArray(5), 0xBEEF).isEmpty())
    }

    @Test fun clashDnsAnswer() {
        val a = ClashApi.parseDnsAnswer("""{"Status":0,"Answer":[{"type":1,"data":"203.0.113.1"},{"type":28,"data":"2001:db8::1"},{"type":1,"data":"203.0.113.2"}]}""")
        assertEquals(0, a.status)
        assertEquals(listOf("203.0.113.1", "203.0.113.2"), a.addresses.map { it.hostAddress })
        assertEquals(-1, ClashApi.parseDnsAnswer("garbage").status)
    }

    // ------------------------------------------------------------------ traffic

    @Test fun proxiedTrafficCounter() {
        val c = ProxiedTrafficCounter()
        fun snap(total: Long, vararg conns: Triple<String, Long, String>) =
            """{"uploadTotal":0,"downloadTotal":$total,"connections":[${conns.joinToString(",") { """{"id":"${it.first}","upload":0,"download":${it.second},"chains":["${it.third}"]}""" }}]}"""
        assertTrue(c.apply(snap(300, Triple("a", 100, "proxy-out"), Triple("b", 200, "direct-out"))))
        assertEquals(100L, c.proxiedBytes)
        assertEquals(200L, c.directBytes)
        // "a" closed after 50 more bytes: the core's total explains them, and only a VPN connection closed
        assertTrue(c.apply(snap(350, Triple("b", 200, "direct-out"))))
        assertEquals(150L, c.proxiedBytes)
        assertEquals(350L, c.proxiedBytes + c.directBytes)
        assertFalse(c.apply("not json"))
    }

    // ------------------------------------------------------------------ texts

    @Test fun texts() {
        assertEquals("1.50 GB", Format.traffic((1.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("12.0 KB/s", Format.speed(12 * 1024))
        assertEquals("2.00 MB/s", Format.speed(2 * 1024 * 1024))
        assertEquals("01:02:03", Format.duration((3600 + 120 + 3) * 1000L))
        assertEquals("1 сервер", Format.servers(1))
        assertEquals("3 сервера", Format.servers(3))
        assertEquals("11 серверов", Format.servers(11))
        assertEquals("22 сервера", Format.servers(22))
        val now = System.currentTimeMillis()
        val plan = Format.plan(SubscriptionEntry(download = 95L * 1024 * 1024 * 1024, total = 100L * 1024 * 1024 * 1024, expire = now + 2 * 86_400_000L), now)
        assertTrue(plan.text, plan.text.startsWith("Осталось 5 ГБ из 100 ГБ · ещё 2 дня"))
        assertTrue(plan.isWarning)
        assertEquals(0.95f, plan.usedFraction, 0.001f)
        assertEquals("Израсходовано 1,5 ГБ · без лимита", Format.plan(SubscriptionEntry(download = (1.5 * 1024 * 1024 * 1024).toLong()), now).text)
        assertTrue(Format.`when`(now, now).startsWith("сегодня в "))
        assertEquals("panel.example.com", Format.subscriptionName("", "https://panel.example.com/sub/token"))
        assertEquals("Германия, Франкфурт", Format.countryAndCity("Германия, Франкфурт, Hessen"))
    }

    @Test fun settingsSerialiseWithoutRuntimeState() {
        val p = ProxyItem(name = "N", host = "h").apply { isFavorite = true; status = ProxyStatus.Online; pingMs = 10; workingAddress = "1.2.3.4" }
        val json = Json { encodeDefaults = true }
        val text = json.encodeToString(AppConfig.serializer(), AppConfig(proxies = mutableListOf(p)))
        assertFalse("Check results are not saved", text.contains("1.2.3.4"))
        val back = Json { ignoreUnknownKeys = true }.decodeFromString(AppConfig.serializer(), text)
        assertTrue(back.proxies[0].isFavorite)
        assertEquals(ProxyStatus.Unknown, back.proxies[0].status)
        assertNotNull((Json.parseToJsonElement(text) as JsonObject)["proxies"] as? JsonArray)
        assertTrue(((Json.parseToJsonElement(text) as JsonObject)["killSwitch"] as JsonPrimitive).content == "false")
    }
}
