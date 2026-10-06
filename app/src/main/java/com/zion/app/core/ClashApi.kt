package com.zion.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.coroutines.coroutineContext

class ClashDelayResult(val success: Boolean, val delayMs: Int = -1, val statusCode: Int = 0, val errorMessage: String = "")

class DnsAnswer(val status: Int, val addresses: List<InetAddress>)

/** The core's control API on loopback. Every call is blocking-safe (runs on IO) and never throws. */
class ClashApi(private val endpoint: ControlEndpoint) {
    private val base = "http://127.0.0.1:${endpoint.port}"

    private fun open(path: String, timeoutMs: Int): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
            setRequestProperty("Authorization", "Bearer ${endpoint.secret}")
        }

    private fun readBody(c: HttpURLConnection): String {
        val stream = if (c.responseCode in 200..299) c.inputStream else (c.errorStream ?: return "")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** Why the last [isAlive] said no (for the log), empty when it said yes. */
    @Volatile var lastError = ""
        private set

    suspend fun isAlive(timeoutMs: Int = 500): Boolean = withContext(Dispatchers.IO) {
        try {
            val c = open("/version", timeoutMs)
            try {
                val ok = c.responseCode in 200..299
                lastError = if (ok) "" else "HTTP "
                ok
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    suspend fun delay(proxyName: String, testUrl: String = "https://www.gstatic.com/generate_204", timeoutMs: Int = 3000): ClashDelayResult =
        withContext(Dispatchers.IO) {
            try {
                val c = open("/proxies/${escapeData(proxyName)}/delay?url=${escapeData(testUrl)}&timeout=$timeoutMs", timeoutMs + 1500)
                try {
                    val code = c.responseCode
                    val body = readBody(c)
                    val obj = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }
                    if (code in 200..299) {
                        val d = (obj?.get("delay") as? JsonPrimitive)?.intOrNull
                        if (d != null) ClashDelayResult(true, d, code)
                        else ClashDelayResult(false, -1, code, "Ответ Clash API не содержит поля 'delay'.")
                    } else {
                        ClashDelayResult(false, -1, code, (obj?.get("message") as? JsonPrimitive)?.contentOrNull ?: body)
                    }
                } finally {
                    c.disconnect()
                }
            } catch (e: java.net.SocketTimeoutException) {
                ClashDelayResult(false, errorMessage = "Превышено время ожидания ответа от Clash API (таймаут).")
            } catch (e: Exception) {
                ClashDelayResult(false, errorMessage = e.message ?: e.javaClass.simpleName)
            }
        }

    /**
     * Asks the core's own DNS for the IPv4 addresses of a name.
     * Status is the DNS response code (0 = fine, 3 = no such name); -1 = the core could not answer.
     */
    suspend fun queryDns(name: String, timeoutMs: Int = 4000): DnsAnswer = withContext(Dispatchers.IO) {
        try {
            val c = open("/dns/query?name=${escapeData(name)}&type=A", timeoutMs)
            try {
                if (c.responseCode !in 200..299) DnsAnswer(-1, emptyList()) else parseDnsAnswer(readBody(c))
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            DnsAnswer(-1, emptyList())
        }
    }

    suspend fun connections(): String? = withContext(Dispatchers.IO) {
        try {
            val c = open("/connections", 3000)
            try { if (c.responseCode in 200..299) readBody(c) else null } finally { c.disconnect() }
        } catch (_: Exception) {
            null
        }
    }

    /** Streams /traffic (one JSON line per second) until cancelled; reconnects on errors. */
    suspend fun streamTraffic(onSample: (up: Long, down: Long) -> Unit) = withContext(Dispatchers.IO) {
        while (coroutineContext.isActive) {
            var c: HttpURLConnection? = null
            try {
                c = open("/traffic", 5000).apply { readTimeout = 0 }
                BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
                    while (coroutineContext.isActive) {
                        val line = reader.readLine() ?: break
                        val obj = try { Json.parseToJsonElement(line) as? JsonObject } catch (_: Exception) { null } ?: continue
                        onSample((obj["up"] as? JsonPrimitive)?.longOrNull ?: 0, (obj["down"] as? JsonPrimitive)?.longOrNull ?: 0)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                c?.disconnect()
            }
            if (coroutineContext.isActive) delay(1000)
        }
    }

    companion object {
        /** Reads the core's /dns/query reply: {"Status":0,"Answer":[{"type":1,"data":"1.2.3.4"},...]}. */
        fun parseDnsAnswer(json: String): DnsAnswer {
            val list = mutableListOf<InetAddress>()
            return try {
                val root = Json.parseToJsonElement(json) as JsonObject
                val status = (root["Status"] as? JsonPrimitive)?.intOrNull ?: -1
                (root["Answer"] as? JsonArray)?.forEach { a ->
                    val o = a as? JsonObject ?: return@forEach
                    val type = (o["type"] as? JsonPrimitive)?.intOrNull
                    val data = (o["data"] as? JsonPrimitive)?.contentOrNull
                    if (type == 1 && data != null && isIpLiteral(data) && !data.contains(':')) {
                        val ip = InetAddress.getByName(data)
                        if (ip !in list) list += ip
                    }
                }
                DnsAnswer(status, list)
            } catch (_: Exception) {
                DnsAnswer(-1, list)
            }
        }
    }
}
