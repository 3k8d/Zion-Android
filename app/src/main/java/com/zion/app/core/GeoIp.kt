package com.zion.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** "Country, City" of a server by its address (ipwho.is, then freeipapi.com), like Windows. */
object GeoIp {
    private val cache = ConcurrentHashMap<String, String>()
    private val throttle = Semaphore(2)

    private fun get(url: String): JsonObject? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 4000; readTimeout = 4000 }
        try {
            if (c.responseCode !in 200..299) null
            else Json.parseToJsonElement(c.inputStream.bufferedReader().use { it.readText() }) as? JsonObject
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""

    suspend fun resolveCountryForHost(host: String): String {
        if (host.isBlank()) return ""
        val clean = host.trim().substringBefore(':')
        cache[clean]?.let { return it }
        return throttle.withPermit {
            try {
                withContext(Dispatchers.IO) {
                    var query = clean
                    if (!isIpLiteral(clean)) runCatching {
                        InetAddress.getAllByName(clean).firstOrNull { it.address.size == 4 }?.hostAddress?.let { query = it }
                    }
                    get("https://ipwho.is/$query")?.let { root ->
                        if ((root["success"] as? JsonPrimitive)?.booleanOrNull == true) {
                            val country = root.s("country")
                            val city = root.s("city")
                            val result = Countries.normalizeLocation(if (city.isNotEmpty()) "$country, $city" else country)
                            if (result.isNotBlank()) {
                                cache[clean] = result
                                return@withContext result
                            }
                        }
                    }
                    get("https://freeipapi.com/api/json/$clean")?.let { root ->
                        val country = root.s("countryName")
                        val city = root.s("cityName")
                        if (country.isNotEmpty()) {
                            val result = Countries.normalizeLocation(if (city.isNotEmpty()) "$country, $city" else country)
                            if (result.isNotBlank()) {
                                cache[clean] = result
                                return@withContext result
                            }
                        }
                    }
                    ""
                }
            } finally {
                delay(100)
            }
        }
    }
}
