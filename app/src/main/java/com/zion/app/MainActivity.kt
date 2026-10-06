package com.zion.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import com.zion.app.ui.ZionRoot

class MainActivity : ComponentActivity() {
    private val vm get() = Zion.main

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vm.onVpnPermissionResult(result.resultCode == RESULT_OK)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        // The VPN's notification (with its "Отключить" button) needs this on Android 13+
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            BackHandler { if (!vm.back()) moveTaskToBack(true) }
            val request = vm.vpnPermissionRequest
            LaunchedEffect(request) { if (request != null) vpnPermission.launch(request) }
            ZionRoot(vm)
        }
        if (savedInstanceState == null) vm.onAppStarted()
    }

    override fun onStart() {
        super.onStart()
        vm.setTrafficVisible(true)
    }

    override fun onStop() {
        vm.setTrafficVisible(false)
        super.onStop()
    }
}
