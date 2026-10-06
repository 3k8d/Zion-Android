package com.zion.app.vpn

import com.zion.app.core.DnsMessage
import com.zion.app.core.DohTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Checks DNS-over-HTTPS servers the way the tunnel actually uses them: through the VPN (the app itself is
 * inside the tunnel), over TLS to the provider's host name, with a real DNS question. A server that only
 * accepts a TCP connection but does not answer DNS counts as failed.
 */
object DohProbe {
    private const val TEST_NAME = "example.com"
    private val random = SecureRandom()

    /** Round-trip time in ms of a real DNS answer through the tunnel, or null if it failed. */
    suspend fun probe(target: DohTarget, timeoutMs: Int = 4000): Int? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs.toLong()) {
            val start = System.nanoTime()
            try {
                Socket().use { raw ->
                    raw.tcpNoDelay = true
                    raw.soTimeout = timeoutMs
                    raw.connect(InetSocketAddress(target.ip, 443), timeoutMs)
                    val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, target.host, 443, true) as SSLSocket
                    tls.use { s ->
                        s.sslParameters = s.sslParameters.apply { serverNames = listOf(SNIHostName(target.host)) }
                        s.startHandshake()
                        val id = random.nextInt(0xFFFF) + 1
                        val dns = Base64.getUrlEncoder().withoutPadding().encodeToString(DnsMessage.buildQuery(TEST_NAME, id))
                        val sep = if (target.path.contains('?')) "&" else "?"
                        val request = "GET ${target.path}${sep}dns=$dns HTTP/1.1\r\nHost: ${target.host}\r\n" +
                            "Accept: application/dns-message\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n"
                        s.outputStream.write(request.toByteArray(Charsets.US_ASCII))
                        s.outputStream.flush()
                        val (status, body) = parseHttpResponse(readAll(s.inputStream, 64 * 1024))
                        if (status != 200 || body.isEmpty()) return@withTimeoutOrNull null
                        if (DnsMessage.parseAddresses(body, id).isEmpty()) null
                        else maxOf(1, ((System.nanoTime() - start) / 1_000_000).toInt())
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Checks all targets at once; working ones come back fastest first. */
    suspend fun rank(targets: List<DohTarget>): List<Pair<DohTarget, Int>> = coroutineScope {
        targets.map { t -> async { t to probe(t, 4000) } }.awaitAll()
            .mapNotNull { (t, ms) -> ms?.let { t to it } }
            .sortedBy { it.second }
    }

    private fun readAll(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < limit) {
            val n = try { input.read(buf) } catch (e: java.io.IOException) { if (out.size() > 0) break else throw e }
            if (n <= 0) break
            out.write(buf, 0, n)
            if (isComplete(out.toByteArray())) break
        }
        return out.toByteArray()
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private val CRLFCRLF = "\r\n\r\n".toByteArray()

    /** True once the headers and the whole Content-Length body (or the last chunk) are in. */
    fun isComplete(data: ByteArray): Boolean {
        val headerEnd = indexOf(data, CRLFCRLF)
        if (headerEnd < 0) return false
        val headers = String(data, 0, headerEnd, Charsets.US_ASCII).lowercase()
        val bodyLen = data.size - headerEnd - 4
        val cl = headers.indexOf("content-length:")
        if (cl >= 0) {
            val end = headers.indexOf('\r', cl).let { if (it < 0) headers.length else it }
            return headers.substring(cl + 15, end).trim().toIntOrNull()?.let { bodyLen >= it } ?: false
        }
        if (headers.contains("transfer-encoding: chunked")) return indexOf(data, "0\r\n\r\n".toByteArray(), headerEnd + 4) >= 0
        return false
    }

    /** Status code and body of a raw HTTP/1.1 response (handles Content-Length and chunked bodies). */
    fun parseHttpResponse(data: ByteArray): Pair<Int, ByteArray> {
        val headerEnd = indexOf(data, CRLFCRLF)
        if (headerEnd < 0) return 0 to ByteArray(0)
        val headers = String(data, 0, headerEnd, Charsets.US_ASCII)
        val status = headers.split("\r\n")[0].split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        val body = data.copyOfRange(headerEnd + 4, data.size)
        val lower = headers.lowercase()
        if (lower.contains("transfer-encoding: chunked")) {
            val out = ByteArrayOutputStream()
            var pos = 0
            while (pos < body.size) {
                val lineEnd = indexOf(body, "\r\n".toByteArray(), pos)
                if (lineEnd < 0) break
                val size = String(body, pos, lineEnd - pos, Charsets.US_ASCII).split(';')[0].trim().toIntOrNull(16) ?: break
                pos = lineEnd + 2
                if (size == 0 || pos + size > body.size) break
                out.write(body, pos, size)
                pos += size + 2
            }
            return status to out.toByteArray()
        }
        val cl = lower.indexOf("content-length:")
        if (cl >= 0) {
            val end = lower.indexOf('\r', cl).let { if (it < 0) lower.length else it }
            lower.substring(cl + 15, end).trim().toIntOrNull()?.let { return status to body.copyOf(minOf(it, body.size)) }
        }
        return status to body
    }
}

/**
 * Checks that go around the tunnel on purpose: a socket bound to the real network leaves through it even
 * while the VPN owns the default route. Tells "the VPN server died" apart from "the internet itself is down".
 */
object InternetProbe {
    private val LANDMARKS = listOf("1.1.1.1", "8.8.8.8", "77.88.8.8", "9.9.9.9")

    /** True when a landmark answers past the tunnel; null when there is no real network to test through. */
    suspend fun isInternetReachable(timeoutMs: Int = 2500): Boolean? {
        val network = NetworkMonitor.underlying ?: return null
        return withTimeoutOrNull(timeoutMs.toLong()) {
            coroutineScope {
                val attempts = LANDMARKS.map { ip ->
                    async(Dispatchers.IO) {
                        try {
                            Socket().use { s ->
                                network.bindSocket(s)
                                s.connect(InetSocketAddress(ip, 443), timeoutMs)
                                true
                            }
                        } catch (_: Exception) {
                            false
                        }
                    }
                }
                var reachable = false
                for (a in attempts) if (a.await()) { reachable = true; break }
                attempts.forEach { it.cancel() }
                reachable
            }
        } ?: false
    }
}
