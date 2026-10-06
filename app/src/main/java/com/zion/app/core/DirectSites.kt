package com.zion.app.core

import java.net.IDN
import java.net.URI

/**
 * Sites the user sends around the VPN. A site always includes its subdomains
 * ("yandex.ru" also covers "music.yandex.ru"); matching is by whole name parts.
 */
object DirectSites {

    class Normalized(val domain: String?, val error: String)

    /**
     * Turns whatever the user pasted ("https://www.kinopoisk.ru/film/1", "КИНОПОИСК.РФ", "*.vk.com")
     * into the bare site name the core compares against ("kinopoisk.ru", "xn--...", "vk.com").
     */
    fun normalize(input: String?): Normalized {
        var s = (input ?: "").trim()
        if (s.isEmpty()) return Normalized(null, "Введите адрес сайта")

        if (s.contains("://")) try { URI(s).host?.let { s = it } } catch (_: Exception) {}

        val cut = s.indexOfAny(charArrayOf('/', '?', '#', ' '))
        if (cut >= 0) s = s.substring(0, cut)
        val colon = s.lastIndexOf(':')
        if (colon > 0 && s.indexOf(':') == colon) s = s.substring(0, colon)

        s = s.trim().trimEnd('.').lowercase()
        while (s.startsWith("*.") || s.startsWith(".")) s = s.trimStart('*').trimStart('.')
        if (s.startsWith("www.")) s = s.substring(4) // subdomains are included anyway

        if (isIpLiteral(s)) return Normalized(null, "Нужен адрес сайта, а не IP")

        s = try {
            IDN.toASCII(s, IDN.ALLOW_UNASSIGNED).lowercase() // кинопоиск.рф -> xn--...
        } catch (_: Exception) {
            return Normalized(null, "Это не похоже на адрес сайта")
        }

        val labels = s.split('.')
        val valid = labels.size >= 2 && s.length <= 253 && labels.all { isLabel(it) } && !labels.last().all { it.isDigit() }
        return if (valid) Normalized(s, "") else Normalized(null, "Это не похоже на адрес сайта")
    }

    /** Readable form for the list: punycode back to letters ("xn--p1ai" -> "рф"). */
    fun display(domain: String): String = try { IDN.toUnicode(domain, IDN.ALLOW_UNASSIGNED) } catch (_: Exception) { domain }

    /** Clean, de-duplicated list for the routing rule; anything invalid is dropped. */
    fun sanitize(domains: Iterable<String>?): List<String> {
        val result = mutableListOf<String>()
        domains ?: return result
        for (d in domains) {
            val n = normalize(d).domain
            if (n != null && n !in result) result += n
        }
        return result
    }

    private fun isLabel(l: String) =
        l.length in 1..63 && !l.startsWith('-') && !l.endsWith('-') && l.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
}
