package com.michael.netguardplus

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.michael.netguardplus.domain.model.FamilyDnsProvider
import com.michael.netguardplus.presentation.dashboard.DashboardEffect
import com.michael.netguardplus.presentation.dashboard.DashboardIntent
import com.michael.netguardplus.presentation.dashboard.DashboardScreen
import com.michael.netguardplus.presentation.dashboard.DashboardViewModel
import com.michael.netguardplus.system.permission.UsageAccessPermission
import com.michael.netguardplus.system.stats.TrafficMonitorService
import com.michael.netguardplus.system.vpn.LocalVpnService
import com.michael.netguardplus.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var pendingDnsProvider: FamilyDnsProvider? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val provider = pendingDnsProvider
        pendingDnsProvider = null
        if (result.resultCode == RESULT_OK && provider != null) {
            LocalVpnService.applyDns(this, provider)
            Toast.makeText(this, "Applying ${provider.label}…", Toast.LENGTH_SHORT).show()
        } else if (provider != null) {
            Toast.makeText(
                this,
                "VPN permission is required to change system DNS.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            delay(2000)
            requestNotificationPermissionIfNeeded()
        }

        setContent {
            MyApplicationTheme {
                val appContainer = (application as NetGuardApplication).container
                val lifecycleOwner = LocalLifecycleOwner.current

                var hasUsageAccess by remember { mutableStateOf(UsageAccessPermission.isGranted(this)) }
                var needsVpnPermission by remember {
                    mutableStateOf(VpnService.prepare(this@MainActivity) != null)
                }
                var foregroundMonitoringEnabled by remember {
                    mutableStateOf(TrafficMonitorService.isForegroundMonitoringEnabled(this@MainActivity))
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            hasUsageAccess = UsageAccessPermission.isGranted(this@MainActivity)
                            needsVpnPermission = VpnService.prepare(this@MainActivity) != null
                            foregroundMonitoringEnabled =
                                TrafficMonitorService.isForegroundMonitoringEnabled(this@MainActivity)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val viewModel: DashboardViewModel = viewModel(
                    factory = DashboardViewModel.Factory(
                        getLiveTraffic = appContainer.getLiveTrafficUseCase,
                        getDnsLog = appContainer.getDnsLogUseCase,
                        blocklistRepo = appContainer.blocklistRepository,
                        alertRepo = appContainer.alertRepository,
                        trafficRepo = appContainer.trafficRepository,
                        hotspotRepo = appContainer.hotspotRepository,
                        dnsBlockingRepository = appContainer.dnsBlockingRepository,
                        usageHistoryRepo = appContainer.usageHistoryRepository,
                        networkStatsPoller = appContainer.networkStatsPoller,
                        configureDataAlertUseCase = appContainer.configureDataAlertUseCase
                    )
                )

                val state by viewModel.uiState.collectAsState()
                var showBatteryOptimizationDialog by remember { mutableStateOf(false) }

                DisposableEffect(lifecycleOwner, viewModel) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            viewModel.onIntent(DashboardIntent.RefreshData)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                LaunchedEffect(viewModel.effects) {
                    viewModel.effects.collect { effect ->
                        when (effect) {
                            is DashboardEffect.ShowToast -> {
                                Toast.makeText(this@MainActivity, effect.message, Toast.LENGTH_SHORT).show()
                            }
                            is DashboardEffect.ApplyDnsProvider -> {
                                applyDnsProvider(effect.provider) {
                                    needsVpnPermission = it
                                }
                            }
                            DashboardEffect.EnsureAlertMonitoring -> {
                                if (foregroundMonitoringEnabled) {
                                    TrafficMonitorService.start(this@MainActivity)
                                    requestNotificationPermissionIfNeeded()
                                    if (needsBatteryOptimizationPrompt()) {
                                        showBatteryOptimizationDialog = true
                                    }
                                } else {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Foreground monitoring is off. Turn it on to monitor alerts while the app is closed.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                }

                if (showBatteryOptimizationDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            showBatteryOptimizationDialog = false
                            onBatteryOptimizationDeclined()
                        },
                        title = { Text("Allow background monitoring?") },
                        text = {
                            Text(
                                "NetGuard Pulse checks your data usage in the background and sends an alert when a limit is reached.\n\n" +
                                    "Some phones pause apps to save battery, which can stop alerts when the app is closed. " +
                                    "Allowing unrestricted battery use helps alerts arrive on time.\n\n" +
                                    "You can change this later in system battery settings."
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showBatteryOptimizationDialog = false
                                    openBatteryOptimizationSettings()
                                }
                            ) {
                                Text("Allow")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    showBatteryOptimizationDialog = false
                                    onBatteryOptimizationDeclined()
                                }
                            ) {
                                Text("Not now")
                            }
                        }
                    )
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DashboardScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding),
                        hasUsageAccess = hasUsageAccess,
                        needsVpnPermission = needsVpnPermission,
                        onRequestUsageAccess = {
                            UsageAccessPermission.openSettings(this@MainActivity)
                        },
                        onApplySelectedDns = {
                            applyDnsProvider(state.selectedFamilyDnsProvider) {
                                needsVpnPermission = it
                            }
                        },
                        foregroundMonitoringEnabled = foregroundMonitoringEnabled,
                        onForegroundMonitoringChanged = { enabled ->
                            foregroundMonitoringEnabled = enabled
                            TrafficMonitorService.setForegroundMonitoringEnabled(this@MainActivity, enabled)
                            if (enabled) {
                                TrafficMonitorService.start(this@MainActivity)
                                requestNotificationPermissionIfNeeded()
                                if (needsBatteryOptimizationPrompt()) {
                                    showBatteryOptimizationDialog = true
                                }
                                Toast.makeText(
                                    this@MainActivity,
                                    "Foreground monitoring enabled.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                TrafficMonitorService.stop(this@MainActivity)
                                Toast.makeText(
                                    this@MainActivity,
                                    "Foreground monitoring stopped.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
            }
        }
    }

    private fun applyDnsProvider(
        provider: FamilyDnsProvider,
        onNeedsPermissionChanged: (Boolean) -> Unit = {}
    ) {
        if (provider == FamilyDnsProvider.SYSTEM_DEFAULT) {
            LocalVpnService.requestStop(this)
            onNeedsPermissionChanged(false)
            Toast.makeText(this, "Using system DNS.", Toast.LENGTH_SHORT).show()
            return
        }

        val prepareIntent = VpnService.prepare(this)
        onNeedsPermissionChanged(prepareIntent != null)
        if (prepareIntent != null) {
            pendingDnsProvider = provider
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            pendingDnsProvider = null
            LocalVpnService.applyDns(this, provider)
            Toast.makeText(this, "Applying ${provider.label}…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun needsBatteryOptimizationPrompt(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return !powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Could not open battery settings. Enable unrestricted use manually in system settings.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun onBatteryOptimizationDeclined() {
        Toast.makeText(
            this,
            "Alert enabled. Background monitoring may be delayed if battery optimization stays on.",
            Toast.LENGTH_LONG
        ).show()
    }
}
