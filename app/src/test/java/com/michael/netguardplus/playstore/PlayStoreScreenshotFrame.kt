package com.michael.netguardplus.playstore

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.michael.netguardplus.presentation.dashboard.DashboardScreenContent
import com.michael.netguardplus.presentation.dashboard.DashboardUiState
import com.michael.netguardplus.ui.theme.MyApplicationTheme

enum class PlayStoreScene(val tabIndex: Int) {
  Overview(0),
  Dns(1),
  Alerts(2),
  Hotspot(3),
  History(4),
}

@Composable
fun PlayStoreScreenshotFrame(
  scene: PlayStoreScene,
  state: DashboardUiState,
) {
  MyApplicationTheme(dynamicColor = false) {
    DashboardScreenContent(
      state = state,
      onIntent = {},
      hasUsageAccess = true,
      needsVpnPermission = false,
      initialTab = scene.tabIndex,
      animationsEnabled = false,
    )
  }
}

@Composable
fun PlayStoreAlertNetworkOverlay(state: DashboardUiState) {
  MyApplicationTheme(dynamicColor = false) {
    Box(Modifier.fillMaxSize()) {
      DashboardScreenContent(
        state = state,
        onIntent = {},
        hasUsageAccess = true,
        needsVpnPermission = false,
        initialTab = PlayStoreScene.Alerts.tabIndex,
        animationsEnabled = false,
      )
      PlayStoreAlertNetworkDialog()
    }
  }
}
