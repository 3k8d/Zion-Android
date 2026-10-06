package com.zion.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import android.system.OsConstants
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import io.nekohasekai.libbox.NetworkInterface as BoxInterface

/**
 * What the core asks of Android. One instance per running core: the tunnel opens the VPN interface
 * through [ZionVpnService]; the server check has no tunnel ([tunnel] = false) but its sockets are still
 * kept off the VPN with protect(), so it measures the servers themselves.
 */
class Platform(private val context: Context, private val tunnel: Boolean) : io.nekohasekai.libbox.PlatformInterface {

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    /** Every socket the core opens leaves through the real network, never back into Zion's own tunnel. */
    override fun autoDetectInterfaceControl(fd: Int) {
        ZionVpnService.instance?.protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        if (!tunnel) throw IllegalStateException("no tunnel in this core")
        val service = ZionVpnService.instance ?: throw IllegalStateException("VPN service is not running")
        return service.openTun(options)
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int, destinationAddress: String, destinationPort: Int): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw UnsupportedOperationException()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val uid = cm.getConnectionOwnerUid(ipProtocol, InetSocketAddress(sourceAddress, sourcePort), InetSocketAddress(destinationAddress, destinationPort))
        if (uid == android.os.Process.INVALID_UID) throw IllegalStateException("connection owner not found")
        val owner = ConnectionOwner()
        owner.userId = uid
        val packages = context.packageManager.getPackagesForUid(uid)?.toList() ?: emptyList()
        owner.setAndroidPackageNames(StringList(packages))
        return owner
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = NetworkMonitor.addListener(listener)

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = NetworkMonitor.removeListener(listener)

    override fun getInterfaces(): NetworkInterfaceIterator {
        val result = mutableListOf<BoxInterface>()
        for (network in NetworkMonitor.allNetworks()) {
            val lp = NetworkMonitor.linkProperties(network) ?: continue
            val caps = NetworkMonitor.capabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val name = lp.interfaceName ?: continue
            val nif = runCatching { java.net.NetworkInterface.getByName(name) }.getOrNull() ?: continue
            val box = BoxInterface()
            box.name = name
            box.index = nif.index
            box.mtu = runCatching { nif.mtu }.getOrDefault(1500)
            box.addresses = StringList(lp.linkAddresses.map { "${it.address.hostAddress?.substringBefore('%')}/${it.prefixLength}" })
            var flags = 0
            if (runCatching { nif.isUp }.getOrDefault(false)) flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            if (runCatching { nif.isLoopback }.getOrDefault(false)) flags = flags or OsConstants.IFF_LOOPBACK
            if (runCatching { nif.isPointToPoint }.getOrDefault(false)) flags = flags or OsConstants.IFF_POINTOPOINT
            if (runCatching { nif.supportsMulticast() }.getOrDefault(false)) flags = flags or OsConstants.IFF_MULTICAST
            if (nif.interfaceAddresses.any { it.broadcast != null }) flags = flags or OsConstants.IFF_BROADCAST
            box.flags = flags
            box.type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            box.dnsServer = StringList(lp.dnsServers.mapNotNull { it.hostAddress })
            box.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            result += box
        }
        return InterfaceList(result)
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun readWIFIState(): WIFIState? = null
    override fun clearDNSCache() {}
    override fun sendNotification(notification: Notification) {}
    override fun cancelNotification(identifier: String, typeID: Int) {}
    override fun startNeighborMonitor(listener: NeighborUpdateListener) {}
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun registerMyInterface(name: String) {}
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() = throw UnsupportedOperationException()
    override fun openShellSession(user: PlatformUser?, command: String?, environ: StringIterator?, term: String?, rows: Int, cols: Int): ShellSession =
        throw UnsupportedOperationException()
    override fun lookupUser(username: String?): PlatformUser = throw UnsupportedOperationException()
    override fun lookupSFTPServer(): String = throw UnsupportedOperationException()
    override fun readSystemSSHHostKey(): String = throw UnsupportedOperationException()
    override fun tailscaleHostname(): String = ""
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = throw UnsupportedOperationException()

    /** The core's "local" DNS: the real network's resolver, asked directly (never through the tunnel). */
    override fun localDNSTransport(): LocalDNSTransport = LocalResolver
}

class StringList(private val values: List<String>) : StringIterator {
    private var i = 0
    override fun hasNext() = i < values.size
    override fun len() = values.size
    override fun next(): String = values[i++]
}

private class InterfaceList(private val values: List<BoxInterface>) : NetworkInterfaceIterator {
    private var i = 0
    override fun hasNext() = i < values.size
    override fun next(): BoxInterface = values[i++]
}

/** Resolver bound to the real network: DnsResolver on Android 10+, the network's own lookup before that. */
object LocalResolver : LocalDNSTransport {
    private val executor = Executors.newCachedThreadPool()

    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw UnsupportedOperationException()
        val network = NetworkMonitor.underlying ?: throw IllegalStateException("no network")
        val cancel = CancellationSignal()
        ctx.onCancel { cancel.cancel() }
        val done = java.util.concurrent.CountDownLatch(1)
        @Suppress("DEPRECATION") // the replacement exists only on the newest Android; this works on all of 10+
        val resolver = DnsResolver.getInstance()
        resolver.rawQuery(network, message, DnsResolver.FLAG_NO_RETRY, executor, cancel,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
                    done.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    val cause = error.cause
                    if (cause is android.system.ErrnoException) ctx.errnoCode(cause.errno) else ctx.errnoCode(OsConstants.EIO)
                    done.countDown()
                }
            })
        done.await()
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val net = NetworkMonitor.underlying ?: throw IllegalStateException("no network")
        try {
            val all = net.getAllByName(domain)
            val wanted = all.filter { if (network == "ip6") it is Inet6Address else if (network == "ip4") it !is Inet6Address else true }
            ctx.success(wanted.mapNotNull { it.hostAddress }.joinToString("\n"))
        } catch (e: java.net.UnknownHostException) {
            ctx.errorCode(3) // NXDOMAIN
        }
    }
}
