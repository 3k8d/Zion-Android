package com.zion.app.core

import java.io.ByteArrayOutputStream
import java.util.Base64

/** Same behaviour as .NET Uri.EscapeDataString: everything but RFC 3986 unreserved characters is %-encoded (UTF-8). */
fun escapeData(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
            c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
        ) sb.append(c.toChar())
        else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
    }
    return sb.toString()
}

/** Same behaviour as .NET Uri.UnescapeDataString: %XX sequences are decoded as UTF-8, '+' stays '+', broken escapes stay as they are. */
fun unescapeData(s: String): String {
    if (!s.contains('%')) return s
    val out = ByteArrayOutputStream(s.length)
    var i = 0
    val bytes = s.toByteArray(Charsets.UTF_8)
    while (i < bytes.size) {
        val b = bytes[i]
        if (b == '%'.code.toByte() && i + 2 < bytes.size) {
            val hi = Character.digit(bytes[i + 1].toInt().toChar(), 16)
            val lo = Character.digit(bytes[i + 2].toInt().toChar(), 16)
            if (hi >= 0 && lo >= 0) {
                out.write(hi * 16 + lo)
                i += 3
                continue
            }
        }
        out.write(b.toInt())
        i++
    }
    return out.toString(Charsets.UTF_8.name())
}

/** Base64 / Base64-URL with or without padding, line breaks and spaces; null if it is not Base64. */
fun tryDecodeBase64(text: String): ByteArray? {
    return try {
        var clean = text.replace("\r", "").replace("\n", "").replace(" ", "").trim()
        if (clean.isEmpty()) return null
        if (clean.contains('%')) clean = try { unescapeData(clean) } catch (_: Exception) { clean }
        clean = clean.replace('-', '+').replace('_', '/')
        when (clean.length % 4) {
            2 -> clean += "=="
            3 -> clean += "="
            1 -> return null
        }
        Base64.getDecoder().decode(clean)
    } catch (_: Exception) {
        null
    }
}

fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/** "a=1&b=x%20y" → map (keys case-insensitive like the Windows version). */
fun parseQuery(query: String): MutableMap<String, String> {
    val result = sortedMapOf<String, String>(String.CASE_INSENSITIVE_ORDER)
    var q = query
    if (q.isBlank()) return result
    if (q.startsWith('?')) q = q.substring(1)
    for (pair in q.split('&')) {
        if (pair.isEmpty()) continue
        val eq = pair.indexOf('=')
        if (eq != -1) result[pair.substring(0, eq).trim()] = unescapeData(pair.substring(eq + 1).trim())
        else result[pair.trim()] = ""
    }
    return result
}

fun Map<String, String>.getOr(key: String, fallback: String): String = this[key] ?: fallback

/** True for a literal IPv4 or IPv6 address (no DNS lookup). */
fun isIpLiteral(s: String): Boolean {
    val t = s.trim()
    if (t.isEmpty()) return false
    if (t.contains(':')) return t.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' } && t.count { it == ':' } >= 2
    val parts = t.split('.')
    return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() <= 255 }
}
