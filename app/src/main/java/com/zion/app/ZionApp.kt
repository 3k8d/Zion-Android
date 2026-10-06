package com.zion.app

import android.app.Application
import com.zion.app.core.ConfigStore
import com.zion.app.vm.MainViewModel
import com.zion.app.vpn.Box
import com.zion.app.vpn.NetworkMonitor

/** Process-wide state: one view model for the screens and the VPN service alike. */
object Zion {
    lateinit var main: MainViewModel
        private set

    internal fun init(app: Application) {
        ConfigStore.init(app)
        NetworkMonitor.init(app)
        Box.setup(app)
        main = MainViewModel(app)
    }
}

class ZionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Zion.init(this)
    }
}
