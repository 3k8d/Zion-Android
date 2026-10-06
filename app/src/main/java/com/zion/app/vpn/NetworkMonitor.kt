package com.zion.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import io.nekohasekai.libbox.InterfaceUpdateListener
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Follows the real (non-VPN) network: Wi-Fi, mobile data, Ethernet. The core binds its own sockets to it
 * and the server check and the internet probe go out through it, never through Zion's own tunnel.
 */
object NetworkMonitor {
    private lateinit var cm: ConnectivityManager
    private val thread = HandlerThread("zion-network").apply { start() }
    val handler = Handler(thread.looper)
    private val listeners = CopyOnWriteArraySet<InterfaceUpdateListener>()

    @Volatile var underlying: Network? = null
        private set

    @Volatile private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        cm = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        when {
            Build.VERSION.SDK_INT >= 31 -> cm.registerBestMatchingNetworkCallback(request, callback, handler)
            Build.VERSION.SDK_INT >= 28 -> cm.requestNetwork(request, callback, handler)
            else -> cm.registerDefaultNetworkCallback(callback, handler)
        }
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            underlying = network
            notifyListeners()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network == underlying || underlying == null) {
                underlying = network
                notifyListeners()
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network == underlying) notifyListeners()
        }

        override fun onLost(network: Network) {
            if (network == underlying) {
                underlying = null
                notifyListeners()
            }
        }
    }

    fun linkProperties(network: Network? = underlying): LinkProperties? = network?.let { cm.getLinkProperties(it) }

    fun capabilities(network: Network? = underlying): NetworkCapabilities? = network?.let { cm.getNetworkCapabilities(it) }

    fun allNetworks(): List<Network> = @Suppress("DEPRECATION") cm.allNetworks.toList()

    /**
     * Called when a core starts: it gets the current network right away (on the calling thread), so its
     * first connections already know where to go. Later changes come from the network callback.
     */
    fun addListener(listener: InterfaceUpdateListener) {
        listeners += listener
        push(listener)
    }

    fun removeListener(listener: InterfaceUpdateListener) {
        listeners -= listener
    }

    private fun notifyListeners() {
        for (l in listeners) push(l)
    }

    private fun push(listener: InterfaceUpdateListener) {
        val network = underlying
        val name = linkProperties(network)?.interfaceName
        if (network == null || name.isNullOrEmpty()) {
            runCatching { listener.updateDefaultInterface("", -1, false, false) }
            return
        }
        // The interface may need a moment to appear after the network is announced
        var index = -1
        for (attempt in 0 until 10) {
            index = runCatching { java.net.NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
            if (index >= 0) break
            Thread.sleep(100)
        }
        val caps = capabilities(network)
        val expensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        runCatching { listener.updateDefaultInterface(name, index, expensive, false) }
    }
}
