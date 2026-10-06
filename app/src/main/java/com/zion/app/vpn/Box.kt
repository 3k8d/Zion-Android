package com.zion.app.vpn

import android.content.Context
import android.os.Build
import com.zion.app.BuildConfig
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom

/** One running copy of the sing-box core inside the app (libbox). */
class Box(context: Context, tunnel: Boolean) : CommandServerHandler {
    private val server = CommandServer(this, Platform(context.applicationContext, tunnel))

    /** Starts the core with this config; throws with the core's own message when it refuses or fails. */
    fun start(config: String) {
        server.startOrReloadService(config, OverrideOptions())
    }

    fun stop() {
        runCatching { server.closeService() }
    }

    fun close() {
        stop()
        runCatching { server.close() }
    }

    override fun serviceStop() {}
    override fun serviceReload() {}
    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
    override fun setSystemProxyEnabled(enabled: Boolean) {}
    override fun triggerNativeCrash() {}
    override fun writeDebugMessage(message: String?) {}
    override fun connectSSHAgent(): Int = throw UnsupportedOperationException()

    companion object {
        @Volatile private var setupDone = false

        /** libbox needs its folders once per process. */
        fun setup(context: Context) {
            if (setupDone) return
            synchronized(this) {
                if (setupDone) return
                val base = context.filesDir
                val working = File(base, "core").apply { mkdirs() }
                val temp = File(context.cacheDir, "core").apply { mkdirs() }
                Libbox.setup(SetupOptions().apply {
                    basePath = base.path
                    workingPath = working.path
                    tempPath = temp.path
                    // https://github.com/golang/go/issues/68760
                    fixAndroidStack = Build.VERSION.SDK_INT in 24..25 || Build.VERSION.SDK_INT >= 28
                    logMaxLines = 300
                    debug = false
                    crashReportSource = "Application"
                    appVersion = BuildConfig.VERSION_CODE.toString()
                    appMarketingVersion = BuildConfig.VERSION_NAME
                })
                setupDone = true
            }
        }

        fun version(): String = Libbox.version()

        /** Validates a config with the core itself (same check as "sing-box check"). Null = fine. */
        fun check(config: String): String? = try {
            Libbox.checkConfig(config)
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }

        /** A loopback port nobody listens on right now. */
        fun freeLoopbackPort(exclude: Int = 0): Int {
            repeat(8) {
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { s ->
                    if (s.localPort != exclude) return s.localPort
                }
            }
            throw IllegalStateException("No free loopback port")
        }

        fun randomSecret(bytes: Int): String {
            val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
            return b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
