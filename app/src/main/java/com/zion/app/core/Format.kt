package com.zion.app.core

import com.zion.app.model.SubscriptionEntry
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/** Texts shown in the UI, worded exactly like the Windows version. */
object Format {
    private val ru = Locale.forLanguageTag("ru-RU")
    private val invariant = DecimalFormatSymbols(Locale.ROOT)
    private val ruSymbols = DecimalFormatSymbols(ru)

    fun traffic(bytes: Long): String = DecimalFormat("0.00", invariant).format(bytes / (1024.0 * 1024 * 1024)) + " GB"

    fun speed(bytesPerSec: Long): String =
        if (bytesPerSec < 1024 * 1024) DecimalFormat("0.0", invariant).format(bytesPerSec / 1024.0) + " KB/s"
        else DecimalFormat("0.00", invariant).format(bytesPerSec / (1024.0 * 1024)) + " MB/s"

    fun duration(ms: Long): String {
        val total = ms / 1000
        // Like .NET's TimeSpan "hh\:mm\:ss": hours wrap at a day
        return "%02d:%02d:%02d".format(total / 3600 % 24, total / 60 % 60, total % 60)
    }

    fun plural(n: Int, one: String, few: String, many: String): String {
        val n10 = n % 10
        val n100 = n % 100
        if (n10 == 1 && n100 != 11) return one
        if (n10 in 2..4 && (n100 < 10 || n100 >= 20)) return few
        return many
    }

    fun servers(n: Int) = "$n ${plural(n, "сервер", "сервера", "серверов")}"

    private fun local(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

    fun `when`(timeMs: Long, nowMs: Long): String {
        val t = local(timeMs)
        val now = local(nowMs)
        val hm = t.format(DateTimeFormatter.ofPattern("HH:mm"))
        return when {
            t.toLocalDate() == now.toLocalDate() -> "сегодня в $hm"
            t.toLocalDate() == now.toLocalDate().minusDays(1) -> "вчера в $hm"
            t.year == now.year -> t.format(DateTimeFormatter.ofPattern("d MMMM 'в' HH:mm", ru))
            else -> t.format(DateTimeFormatter.ofPattern("d MMMM yyyy", ru))
        }
    }

    fun size(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024 * 1024)
        if (gb >= 100) return DecimalFormat("0", ruSymbols).format(gb) + " ГБ"
        if (gb >= 1) return DecimalFormat("0.#", ruSymbols).format(gb) + " ГБ"
        return DecimalFormat("0", ruSymbols).format(bytes / (1024.0 * 1024)) + " МБ"
    }

    class Plan(val text: String, val usedFraction: Float, val isWarning: Boolean)

    /** "Осталось 87,4 ГБ из 100 ГБ · до 15 ноября" and the used share for the bar (0..1). */
    fun plan(e: SubscriptionEntry, nowMs: Long): Plan {
        val parts = mutableListOf<String>()
        var used = 0.0
        var warning = false
        val spent = e.upload + e.download
        if (e.total > 0) {
            val left = maxOf(0L, e.total - spent)
            used = (spent.toDouble() / e.total).coerceIn(0.0, 1.0)
            parts += "Осталось ${size(left)} из ${size(e.total)}"
            if (used >= 0.9) warning = true
        } else if (spent > 0) {
            parts += "Израсходовано ${size(spent)} · без лимита"
        }
        e.expire?.let { expire ->
            val end = local(expire)
            val now = local(nowMs)
            if (expire <= nowMs) {
                parts += "истекла ${end.format(DateTimeFormatter.ofPattern("d MMMM", ru))}"
                warning = true
            } else {
                val days = ceil((expire - nowMs) / 86_400_000.0).toInt()
                parts += if (days <= 7) "ещё $days ${plural(days, "день", "дня", "дней")}"
                else "до " + end.format(DateTimeFormatter.ofPattern(if (end.year == now.year) "d MMMM" else "d MMMM yyyy", ru))
                if (days <= 3) warning = true
            }
        }
        return Plan(parts.joinToString(" · "), used.toFloat(), warning)
    }

    /** Provider's title if known, otherwise the host of the link. */
    fun subscriptionName(title: String?, url: String): String {
        if (!title.isNullOrBlank()) return title.trim()
        return try { java.net.URI(url).host ?: url } catch (_: Exception) { url }
    }

    /** "Германия, Франкфурт" from GeoIP-style text. */
    fun countryAndCity(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var cleaned = raw.trim()
        if (cleaned.startsWith("Flag_", true)) {
            cleaned = cleaned.substring(5).trim()
            val space = cleaned.indexOf(' ')
            if (space in 1..3) cleaned = cleaned.substring(space + 1).trim()
        }
        val parts = cleaned.split(',', '-').map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            parts.size >= 2 -> "${parts[0]}, ${parts[1]}"
            parts.size == 1 -> parts[0]
            else -> cleaned
        }
    }
}
