package com.example

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.presentation.dashboard.DashboardScreen
import com.example.presentation.dashboard.DashboardViewModel
import com.example.system.vpn.LocalVpnService
import com.example.ui.theme.MyApplicationTheme
import com.example.presentation.overlay.FloatingOverlayService

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "Firewall VPN permission is required.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Safely ask for standard notification permission in Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(
                android.Manifest.permission.POST_NOTIFICATIONS
            )
        }

        setContent {
            MyApplicationTheme {
                val appContainer = (application as NetGuardApplication).container
                
                // Construct ViewModel manually inside Compose using Factory
                val viewModel: DashboardViewModel = viewModel(
                    factory = DashboardViewModel.Factory(
                        getLiveTraffic = appContainer.getLiveTrafficUseCase,
                        getDnsLog = appContainer.getDnsLogUseCase,
                        blocklistRepo = appContainer.blocklistRepository,
                        alertRepo = appContainer.alertRepository,
                        trafficRepo = appContainer.trafficRepository,
                        importBlocklistUseCase = appContainer.importBlocklistUseCase,
                        configureDataAlertUseCase = appContainer.configureDataAlertUseCase
                    )
                )

                // Render Beautiful Scaffold and Dashboard Screen
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DashboardScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding),
                        onToggleVpnService = { isEnabled ->
                            if (isEnabled) {
                                prepareAndStartVpn()
                            } else {
                                stopVpnService()
                            }
                            
                            // Also toggle standard floating overlay if VPN is turned on and overlay permissions are met
                            toggleFloatingOverlay(isEnabled)
                        }
                    )
                }

                // Handle Shared effects (Toasts, snacks etc)
                val effects = viewModel.effects
                LaunchedEffect(effects) {
                    effects.collect { effect ->
                        when (effect) {
                            is com.example.presentation.dashboard.DashboardEffect.ShowToast -> {
                                Toast.makeText(this@MainActivity, effect.message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }

        // Guide user to grant standard stats permission to allow delta-math calculations
        if (!hasUsageStatsPermission()) {
            requestUsageStatsPermission()
        }
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, LocalVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpnService() {
        val intent = Intent(this, LocalVpnService::class.java)
        stopService(intent)
    }

    private fun toggleFloatingOverlay(enable: Boolean) {
        if (enable) {
            if (Settings.canDrawOverlays(this)) {
                startService(Intent(this, FloatingOverlayService::class.java))
            } else {
                // Redirect user to grant overlay settings
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        } else {
            stopService(Intent(this, FloatingOverlayService::class.java))
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun requestUsageStatsPermission() {
        Toast.makeText(
            this,
            "Please enable usage statistics permission to analyze traffic.",
            Toast.LENGTH_LONG
        ).show()
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }
}
