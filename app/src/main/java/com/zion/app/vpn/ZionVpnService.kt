package com.zion.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.zion.app.MainActivity
import com.zion.app.R
import com.zion.app.Zion
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.TunOptions
import java.net.InetAddress

/**
 * The Android VPN. It only holds the interface and the notification: the core opens the tunnel through
 * [openTun], and while Kill Switch keeps the internet closed a blocking interface stands in its place.
 */
class ZionVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    private var blockerFd: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            Zion.main.disconnect()
            return START_NOT_STICKY
        }
        startForegroundCompat(notification(Zion.main.notificationText()))
        if (intent?.action == VpnService.SERVICE_INTERFACE) {
            // Started by the system (always-on VPN): connect to the selected server
            Zion.main.connectFromSystem()
        }
        synchronized(readyWaiters) {
            readyWaiters.forEach { it.complete(Unit) }
            readyWaiters.clear()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
    }

    fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ZionVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Zion")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "Отключить", stop).build())
            .build()
    }

    /** Called by the core: builds the VPN interface from its tun options and returns the file descriptor. */
    fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) throw IllegalStateException("Нет разрешения на VPN: откройте Zion и подключитесь ещё раз")
        val builder = Builder().setSession("Zion").setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

        val v4 = options.inet4Address.toList()
        val v6 = options.inet6Address.toList()
        v4.forEach { builder.addAddress(it.address(), it.prefix()) }
        v6.forEach { builder.addAddress(it.address(), it.prefix()) }

        if (options.autoRoute) {
            options.dnsServerAddress.let { while (it.hasNext()) builder.addDnsServer(it.next()) }

            val routes4 = options.inet4RouteRange.toList()
            if (routes4.isNotEmpty()) routes4.forEach { builder.addRoute(it.address(), it.prefix()) }
            else if (v4.isNotEmpty()) builder.addRoute("0.0.0.0", 0)

            val routes6 = options.inet6RouteRange.toList()
            if (routes6.isNotEmpty()) routes6.forEach { builder.addRoute(it.address(), it.prefix()) }
            else if (v6.isNotEmpty()) builder.addRoute("::", 0)

            if (Build.VERSION.SDK_INT >= 33) {
                options.inet4RouteExcludeAddress.toList().forEach { builder.excludeRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix())) }
                options.inet6RouteExcludeAddress.toList().forEach { builder.excludeRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix())) }
            }

            val include = options.includePackage
            if (include.hasNext()) {
                while (include.hasNext()) addPackage(include.next()) { builder.addAllowedApplication(it) }
            } else {
                val exclude = options.excludePackage
                while (exclude.hasNext()) addPackage(exclude.next()) { builder.addDisallowedApplication(it) }
            }
        }

        val pfd = builder.establish() ?: throw IllegalStateException("Android не дал создать VPN-интерфейс")
        tunFd?.close()
        tunFd = pfd
        // The real tunnel replaced the blocking interface (establish() swaps them without a gap)
        blockerFd?.close()
        blockerFd = null
        return pfd.fd
    }

    /** An app that is not installed (or not visible) is simply skipped. */
    private inline fun addPackage(name: String, add: (String) -> Unit) {
        try {
            add(name)
        } catch (_: PackageManager.NameNotFoundException) {
        }
    }

    /** The core closed the tunnel (stop or restart). */
    fun closeTun() {
        tunFd?.close()
        tunFd = null
    }

    /**
     * Kill Switch: an interface that takes all traffic and passes none of it. Established before the old
     * tunnel goes away, so a restart or a failure never runs in the clear. Zion itself stays outside it,
     * so it can still check servers, refresh subscriptions and bring the tunnel back.
     */
    fun holdBlocker(): Boolean {
        if (blockerFd != null && tunFd == null) return true
        return try {
            if (prepare(this) != null) return false
            val pfd = Builder().setSession("Zion · Kill Switch")
                .addAddress("172.19.0.1", 30)
                .addAddress("fdfe:dcba:9876::1", 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDisallowedApplication(packageName)
                .establish() ?: return false
            blockerFd?.close()
            blockerFd = pfd
            true
        } catch (_: Exception) {
            false
        }
    }

    val isBlocking: Boolean get() = blockerFd != null && tunFd == null

    fun releaseBlocker() {
        blockerFd?.close()
        blockerFd = null
    }

    /** Everything is off: the service goes away together with its notification. */
    fun shutdown() {
        closeTun()
        releaseBlocker()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onRevoke() {
        // Another VPN app took over, or the user turned Zion off in Android's settings
        Zion.main.onVpnRevoked()
        closeTun()
        releaseBlocker()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTun()
        releaseBlocker()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "zion_vpn"
        private const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT = "com.zion.app.DISCONNECT"

        @Volatile var instance: ZionVpnService? = null
            private set

        private val readyWaiters = mutableListOf<kotlinx.coroutines.CompletableDeferred<Unit>>()

        /** Starts the service in the foreground and waits until it is up. */
        suspend fun ensureRunning(context: Context) {
            if (instance != null) return
            val waiter = kotlinx.coroutines.CompletableDeferred<Unit>()
            synchronized(readyWaiters) { readyWaiters += waiter }
            context.startForegroundService(Intent(context, ZionVpnService::class.java))
            kotlinx.coroutines.withTimeoutOrNull(10_000) { waiter.await() }
            if (instance == null) throw IllegalStateException("Служба VPN не запустилась")
        }
    }
}

private fun RoutePrefixIterator.toList(): List<io.nekohasekai.libbox.RoutePrefix> {
    val list = mutableListOf<io.nekohasekai.libbox.RoutePrefix>()
    while (hasNext()) list += next()
    return list
}
