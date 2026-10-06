package com.zion.app.core

import com.zion.app.model.ProxyItem
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

/** What one download of a subscription link produced. */
class SubscriptionFetchResult(
    val items: List<ProxyItem> = emptyList(),
    /** Provider's name for the subscription (profile-title or the file name), may be empty. */
    val title: String = "",
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    /** Epoch ms, null = no end date. */
    val expire: Long? = null,
    /** Human-readable reason when no servers came back. */
    val error: String = "",
    /** The answer is definitive (e.g. 404): trying the relay would not change it. */
    val isFinal: Boolean = false,
)

/** Downloads subscriptions. While the VPN is up the request goes through it (the app is inside the tunnel). */
object SubscriptionFetch {
    const val CLOUDFLARE_RELAY_URL = "https://dark-moon-b211.airtoneaokirazer94.workers.dev/?url="

    private val USER_AGENTS = listOf(
        "Happ/3.0.0",
        "sing-box/1.10.0",
        "v2rayNG/1.8.19",
        "ClashMeta/1.18.0",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36",
    )

    private const val MAX_BYTES = 10 * 1024 * 1024

    /** Downloads a subscription: its servers plus what the provider says about the plan. Never throws. */
    fun fetch(rawUrl: String, hwid: String, tunnelUp: Boolean): SubscriptionFetchResult {
        if (rawUrl.isBlank()) return SubscriptionFetchResult(error = "Пустая ссылка")
        val url = ProxyParser.extractActualSubscriptionUrl(rawUrl)
        val uri = try { URI(url) } catch (_: Exception) { return SubscriptionFetchResult(error = "Это не ссылка") }
        if (uri.scheme == null || uri.host == null) return SubscriptionFetchResult(error = "Это не ссылка")
        if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true))
            return SubscriptionFetchResult(error = "Ссылка должна начинаться с http:// или https://")

        if (isLocalHost(uri.host)) return SubscriptionFetchResult(error = "Локальные адреса не поддерживаются")

        // 1. Through the tunnel if it is running, otherwise direct
        val result = execute(url, url, hwid, isRelay = false, tunnelUp = tunnelUp)
        if (result.items.isNotEmpty() || result.isFinal) return result

        // 2. The direct request may have been blocked on the way (timeout, reset, 502): try the relay
        if (!url.contains("workers.dev/?url=")) {
            val relayed = execute(CLOUDFLARE_RELAY_URL + escapeData(url), url, hwid, isRelay = true, tunnelUp = false)
            if (relayed.items.isNotEmpty()) return relayed
        }
        return result
    }

    /** No localhost, private ranges, link-local or cloud metadata addresses (no DNS lookup is made). */
    fun isLocalHost(rawHost: String): Boolean {
        val host = rawHost.trim('[', ']').lowercase()
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "169.254.169.254" ||
            host.endsWith(".internal") || host.endsWith(".local") || host.endsWith(".localhost")
        ) return true
        if (!isIpLiteral(host)) return false
        return try {
            val ip = InetAddress.getByName(host)
            val b = ip.address
            if (b.size == 4) {
                val b0 = b[0].toInt() and 0xFF
                val b1 = b[1].toInt() and 0xFF
                b0 == 127 || b0 == 10 || (b0 == 172 && b1 in 16..31) || (b0 == 192 && b1 == 168) || (b0 == 169 && b1 == 254) || b0 == 0
            } else ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress
        } catch (_: Exception) {
            false
        }
    }

    private fun execute(target: String, originalUrl: String, hwid: String, isRelay: Boolean, tunnelUp: Boolean): SubscriptionFetchResult {
        var error = "Сервер подписки не отвечает"
        val timeoutMs = when {
            isRelay -> 8000
            tunnelUp -> 5000
            else -> 4000
        }

        for (ua in USER_AGENTS) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(target).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("User-Agent", ua)
                    setRequestProperty("X-Hwid", hwid)
                    setRequestProperty("Accept", "*/*")
                }
                val code = conn.responseCode
                if (code == 404) return SubscriptionFetchResult(error = "Подписка не найдена (ошибка 404): возможно, ссылка устарела", isFinal = true)
                if (code == 502 && !isRelay) {
                    error = "Сервер подписки недоступен (ошибка 502)"
                    break
                }
                if (code !in 200..299) {
                    error = if (code == 401 || code == 403) "Доступ к подписке закрыт (ошибка $code): возможно, она истекла"
                    else "Сервер подписки ответил ошибкой $code"
                    continue
                }
                val len = conn.contentLengthLong
                if (len > MAX_BYTES) return SubscriptionFetchResult(error = "Ответ подписки слишком большой", isFinal = true)

                val body = readLimited(conn) ?: return SubscriptionFetchResult(error = "Ответ подписки слишком большой", isFinal = true)
                val content = String(body, Charsets.UTF_8).trim()
                if (content.isBlank()) {
                    error = "Подписка пустая: в ней нет серверов"
                    continue
                }

                val decoded = tryDecodeBase64(content)?.let { String(it, Charsets.UTF_8) } ?: content
                val items = ProxyParser.parseBulk(decoded)
                if (items.isNotEmpty()) {
                    ProxyParser.adoptForSubscription(items, originalUrl)
                    val info = ProxyParser.parseSubscriptionUserInfo(conn.getHeaderField("subscription-userinfo"))
                    var title = ProxyParser.decodeProfileTitle(conn.getHeaderField("profile-title"))
                    if (title.isBlank()) title = fileNameFrom(conn.getHeaderField("Content-Disposition"))
                    return SubscriptionFetchResult(
                        items = items, title = title.trim(), upload = info[0], download = info[1], total = info[2],
                        expire = info[3].takeIf { it > 0 },
                    )
                }
                error = "В ответе не нашлось серверов: ссылка не похожа на подписку"
            } catch (e: Exception) {
                if (e is SocketTimeoutException || e is IOException) {
                    error = if (e is SocketTimeoutException) "Сервер подписки не ответил вовремя" else "Не удалось соединиться с сервером подписки"
                    // A dropped or reset direct connection will not get better with another User-Agent: go to the relay
                    if (!isRelay && !tunnelUp) break
                }
            } finally {
                conn?.disconnect()
            }
        }
        return SubscriptionFetchResult(error = error)
    }

    private fun readLimited(conn: HttpURLConnection): ByteArray? {
        conn.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) return null
            }
            return out.toByteArray()
        }
    }

    /** filename*=UTF-8''name or filename="name" from Content-Disposition. */
    fun fileNameFrom(disposition: String?): String {
        if (disposition.isNullOrBlank()) return ""
        Regex("filename\\*\\s*=\\s*(?:UTF-8''|utf-8'')?([^;]+)", RegexOption.IGNORE_CASE).find(disposition)?.let {
            return unescapeData(it.groupValues[1].trim().trim('"'))
        }
        Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition)?.let {
            return it.groupValues[1].trim()
        }
        return ""
    }
}
