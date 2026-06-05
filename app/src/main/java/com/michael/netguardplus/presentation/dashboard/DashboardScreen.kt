package com.michael.netguardplus.presentation.dashboard

import android.widget.Space
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michael.netguardplus.domain.model.AppTrafficInfo
import com.michael.netguardplus.domain.model.DnsLogEntry
import com.michael.netguardplus.domain.model.DataAlert
import com.michael.netguardplus.domain.model.AlertType
import com.michael.netguardplus.domain.model.AlertNetworkType
import com.michael.netguardplus.domain.model.HotspotClient
import com.michael.netguardplus.domain.model.HotspotSessionConfig
import com.michael.netguardplus.domain.model.HotspotSessionStatus
import com.michael.netguardplus.domain.model.HistoryDateRange
import com.michael.netguardplus.domain.model.UsageCategory
import com.michael.netguardplus.domain.model.UsageHistoryBucket
import com.michael.netguardplus.domain.model.UsageHistoryReport
import com.michael.netguardplus.domain.model.UsageTraffic
import com.michael.netguardplus.domain.model.FamilyDnsProvider
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
    hasUsageAccess: Boolean = true,
    needsVpnPermission: Boolean = false,
    onRequestUsageAccess: () -> Unit = {},
    onApplySelectedDns: () -> Unit = {},
    foregroundMonitoringEnabled: Boolean = true,
    onForegroundMonitoringChanged: (Boolean) -> Unit = {},
    initialTab: Int = 0,
    animationsEnabled: Boolean = true,
) {
    val state by viewModel.uiState.collectAsState()
    DashboardScreenContent(
        state = state,
        onIntent = viewModel::onIntent,
        modifier = modifier,
        hasUsageAccess = hasUsageAccess,
        needsVpnPermission = needsVpnPermission,
        onRequestUsageAccess = onRequestUsageAccess,
        onApplySelectedDns = onApplySelectedDns,
        foregroundMonitoringEnabled = foregroundMonitoringEnabled,
        onForegroundMonitoringChanged = onForegroundMonitoringChanged,
        initialTab = initialTab,
        animationsEnabled = animationsEnabled,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardScreenContent(
    state: DashboardUiState,
    onIntent: (DashboardIntent) -> Unit,
    modifier: Modifier = Modifier,
    hasUsageAccess: Boolean = true,
    needsVpnPermission: Boolean = false,
    onRequestUsageAccess: () -> Unit = {},
    onApplySelectedDns: () -> Unit = {},
    foregroundMonitoringEnabled: Boolean = true,
    onForegroundMonitoringChanged: (Boolean) -> Unit = {},
    initialTab: Int = 0,
    animationsEnabled: Boolean = true,
) {
    var selectedTab by remember { mutableStateOf(initialTab) }
    val tabTitles = listOf("Overview", "DNS", "Alerts", "Hotspot", "History")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "App Shield Logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "NetGuard Pulse",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                fontFamily = FontFamily.SansSerif
                            )
                        )
                    }
                },
                actions = {
                    val dnsStatus = when {
                        state.selectedFamilyDnsProvider == FamilyDnsProvider.SYSTEM_DEFAULT ->
                            "System DNS"
                        state.isDnsActive ->
                            state.selectedFamilyDnsProvider.label
                        else ->
                            "DNS starting…"
                    }
                    val statusColor = when {
                        state.selectedFamilyDnsProvider == FamilyDnsProvider.SYSTEM_DEFAULT ->
                            MaterialTheme.colorScheme.outline
                        state.isDnsActive ->
                            MaterialTheme.colorScheme.primary
                        else ->
                            MaterialTheme.colorScheme.tertiary
                    }
                    Text(
                        text = dnsStatus,
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = statusColor,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(end = 16.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Single-line scrollable tabs
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 12.dp
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium
                                )
                            )
                        }
                    )
                }
            }

            if (!hasUsageAccess) {
                UsageAccessBanner(onOpenSettings = onRequestUsageAccess)
            }

            if (animationsEnabled) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        fadeIn(tween(220)) togetherWith fadeOut(tween(220))
                    },
                    label = "TabContentTransition"
                ) { targetTab ->
                    DashboardTabBody(
                        targetTab = targetTab,
                        state = state,
                        needsVpnPermission = needsVpnPermission,
                        onIntent = onIntent,
                        onApplySelectedDns = onApplySelectedDns,
                        foregroundMonitoringEnabled = foregroundMonitoringEnabled,
                        onForegroundMonitoringChanged = onForegroundMonitoringChanged,
                        animationsEnabled = animationsEnabled,
                    )
                }
            } else {
                DashboardTabBody(
                    targetTab = selectedTab,
                    state = state,
                    needsVpnPermission = needsVpnPermission,
                    onIntent = onIntent,
                    onApplySelectedDns = onApplySelectedDns,
                    foregroundMonitoringEnabled = foregroundMonitoringEnabled,
                    onForegroundMonitoringChanged = onForegroundMonitoringChanged,
                    animationsEnabled = animationsEnabled,
                )
            }
        }
    }
}

@Composable
private fun DashboardTabBody(
    targetTab: Int,
    state: DashboardUiState,
    needsVpnPermission: Boolean,
    onIntent: (DashboardIntent) -> Unit,
    onApplySelectedDns: () -> Unit,
    foregroundMonitoringEnabled: Boolean,
    onForegroundMonitoringChanged: (Boolean) -> Unit,
    animationsEnabled: Boolean,
) {
    when (targetTab) {
        0 -> OverviewTabContent(
            state = state,
            onIntent = onIntent,
            animationsEnabled = animationsEnabled,
        )
        1 -> DnsTabContent(
            state = state,
            needsVpnPermission = needsVpnPermission,
            onIntent = onIntent,
            onApplySelectedDns = onApplySelectedDns,
        )
        2 -> AlertsTabContent(
            state = state,
            onIntent = onIntent,
            foregroundMonitoringEnabled = foregroundMonitoringEnabled,
            onForegroundMonitoringChanged = onForegroundMonitoringChanged,
        )
        3 -> HotspotTabContent(
            state = state,
            onIntent = onIntent,
            animationsEnabled = animationsEnabled,
        )
        4 -> HistoryTabContent(
            state = state,
            onIntent = onIntent,
            animationsEnabled = animationsEnabled,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewTabContent(
    state: DashboardUiState,
    onIntent: (DashboardIntent) -> Unit,
    animationsEnabled: Boolean = true,
) {
    val body: @Composable () -> Unit = {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
        item {
            DeviceDataCard(
                title = "Mobile Data (Today)",
                totalBytes = state.summary.mobileRxBytes + state.summary.mobileTxBytes,
                rxPerSec = state.summary.mobileRxPerSec,
                txPerSec = state.summary.mobileTxPerSec,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        item {
            DeviceDataCard(
                title = "Wi-Fi Data (Today)",
                totalBytes = state.summary.wifiRxBytes + state.summary.wifiTxBytes,
                rxPerSec = state.summary.wifiRxPerSec,
                txPerSec = state.summary.wifiTxPerSec,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        item {
            val hotspotTotalBytes = state.hotspotClients.sumOf { it.rxBytes + it.txBytes }
            val hotspotRxSpeed = state.hotspotClients.filter { it.isConnected }.sumOf { it.rxSpeed }
            val hotspotTxSpeed = state.hotspotClients.filter { it.isConnected }.sumOf { it.txSpeed }
            val hotspotDeviceCount = state.hotspotClients.count { it.isConnected }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Hotspot Data",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                            )
                        )
                        Text(
                            text = when {
                                !state.isHotspotEnabled -> "Off"
                                hotspotDeviceCount == 0 -> "On · scanning…"
                                else -> "On · $hotspotDeviceCount device(s)"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (state.isHotspotEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f)
                                }
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatBytes(hotspotTotalBytes),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "LIVE ↓",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                                )
                            )
                            Text(
                                text = "${formatBytes(hotspotRxSpeed)}/s",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            )
                        }
                        Column {
                            Text(
                                text = "LIVE ↑",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                                )
                            )
                            Text(
                                text = "${formatBytes(hotspotTxSpeed)}/s",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "BLOCKED",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                                )
                            )
                            Text(
                                text = "${state.hotspotClients.count { it.isBlocked }}",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (state.hotspotClients.any { it.isBlocked }) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }

        item {
            // Dashboard live summary card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Total System Transmissions (Today)",
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatBytes(state.summary.totalRxBytes + state.summary.totalTxBytes),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "LIVE ↓",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            )
                            Text(
                                text = "${formatBytes(state.summary.rxPerSec)}/s",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                        Column {
                            Text(
                                text = "LIVE ↑",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            )
                            Text(
                                text = "${formatBytes(state.summary.txPerSec)}/s",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                        Column {
                            Text(
                                text = "BLOCKED REQUESTS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            )
                            Text(
                                text = "${state.summary.blockedRequestsToday}",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Application Traffic",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                )
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.sortOrder == SortOrder.SPEED_DESC,
                    onClick = { onIntent(DashboardIntent.SortChanged(SortOrder.SPEED_DESC)) },
                    label = { Text("Speed") },
                    leadingIcon = if (state.sortOrder == SortOrder.SPEED_DESC) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
                FilterChip(
                    selected = state.sortOrder == SortOrder.DATA_DESC,
                    onClick = { onIntent(DashboardIntent.SortChanged(SortOrder.DATA_DESC)) },
                    label = { Text("Total Data") },
                    leadingIcon = if (state.sortOrder == SortOrder.DATA_DESC) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
                FilterChip(
                    selected = state.sortOrder == SortOrder.NAME_ASC,
                    onClick = { onIntent(DashboardIntent.SortChanged(SortOrder.NAME_ASC)) },
                    label = { Text("Name") },
                    leadingIcon = if (state.sortOrder == SortOrder.NAME_ASC) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
            }
        }

        if (state.appList.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No apps or services using the network right now.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = "Apps and system services update every ~2 seconds while active.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        } else {
            items(state.appList, key = { it.uid }) { app ->
                AppTrafficRowItem(app = app)
            }
        }
    }
    }

    if (animationsEnabled) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { onIntent(DashboardIntent.RefreshData) },
            modifier = Modifier.fillMaxSize(),
        ) {
            body()
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            body()
        }
    }
}

@Composable
fun AppTrafficRowItem(app: AppTrafficInfo) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Visual text initials avatar
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = app.appLabel.take(2).uppercase(),
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appLabel,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "UID: ${app.uid}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Box(
                        modifier = Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                    Text(
                        text = "Total: ${formatBytes(app.rxBytesTotal + app.txBytesTotal)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Real-time rates
            Column(horizontalAlignment = Alignment.End) {
                val sumRate = app.rxBytesPerSec + app.txBytesPerSec
                Text(
                    text = if (sumRate > 0) "${formatBytes(sumRate)}/s" else "idle",
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = if (sumRate > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        fontWeight = FontWeight.Bold
                    )
                )
                if (sumRate > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "↓${formatBytes(app.rxBytesPerSec)} ↑${formatBytes(app.txBytesPerSec)}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun DnsLogTabContent(
    state: DashboardUiState,
    onIntent: (DashboardIntent) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        if (state.dnsLogList.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 50.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No DNS lookups resolved yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = "Turn on the VPN switch at top to intercept local traffic queries.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(state.dnsLogList) { log ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (log.wasBlocked) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (log.wasBlocked) Icons.Default.Warning else Icons.Default.Check,
                            contentDescription = if (log.wasBlocked) "Blocked" else "Allowed",
                            tint = if (log.wasBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = log.domain,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (log.wasBlocked) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = log.packageName.substringAfterLast('.'),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = formatTime(log.timestampMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DnsTabContent(
    state: DashboardUiState,
    needsVpnPermission: Boolean,
    onIntent: (DashboardIntent) -> Unit,
    onApplySelectedDns: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text(
                text = "System DNS",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = "Choose a DNS provider to apply device-wide. All DNS queries are forwarded to that provider's servers.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = "For best results: turn off Private DNS in Android settings, and disable Chrome \"Secure DNS\". Popular adult sites are also blocked locally even if the DNS provider allows them.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            if (state.selectedFamilyDnsProvider != FamilyDnsProvider.SYSTEM_DEFAULT && !state.isDnsActive) {
                Spacer(modifier = Modifier.height(12.dp))
                if (needsVpnPermission) {
                    Text(
                        text = "Tap the button below to show Android's VPN permission dialog. This is required to redirect DNS system-wide.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onApplySelectedDns,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant VPN permission & apply")
                    }
                } else {
                    Text(
                        text = "DNS is not active yet. Tap below to apply ${state.selectedFamilyDnsProvider.label}.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onApplySelectedDns,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Apply ${state.selectedFamilyDnsProvider.label}")
                    }
                }
            }
        }

        items(FamilyDnsProvider.entries.toList()) { provider ->
            val selected = state.selectedFamilyDnsProvider == provider
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onIntent(DashboardIntent.SelectFamilyDnsProvider(provider))
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { onIntent(DashboardIntent.SelectFamilyDnsProvider(provider)) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = provider.label,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = provider.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (provider.serverIps.isNotEmpty()) {
                            Text(
                                text = provider.serverIps.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

// Visual workaround extension to safe modify
private fun Modifier.modifierPadding(padding: Int) = this.padding(horizontal = padding.dp)

@Composable
fun AlertsTabContent(
    state: DashboardUiState,
    onIntent: (DashboardIntent) -> Unit,
    foregroundMonitoringEnabled: Boolean = true,
    onForegroundMonitoringChanged: (Boolean) -> Unit = {}
) {
    var thresholdMb by remember { mutableStateOf("") }
    var selectedTargetUid by remember { mutableStateOf(-1) }
    var selectedTargetPackageName by remember { mutableStateOf("All Transmissions") }
    var expanded by remember { mutableStateOf(false) }
    var showNetworkTypeDialog by remember { mutableStateOf(false) }
    var pendingThresholdMb by remember { mutableStateOf<Long?>(null) }
    var selectedNetworkType by remember { mutableStateOf(AlertNetworkType.MOBILE) }

    val options = listOf(-1 to "Overall Daily Usage") + state.appList.map { it.uid to it.appLabel }
    val selectedOptionText = options.firstOrNull { it.first == selectedTargetUid }?.second ?: "Overall Daily Usage"

    if (showNetworkTypeDialog) {
        AlertDialog(
            onDismissRequest = {
                showNetworkTypeDialog = false
                pendingThresholdMb = null
            },
            title = { Text("Choose Network Type") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Which connection should this limit apply to?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedNetworkType == AlertNetworkType.MOBILE,
                            onClick = { selectedNetworkType = AlertNetworkType.MOBILE },
                            label = { Text("Mobile Data") },
                            leadingIcon = if (selectedNetworkType == AlertNetworkType.MOBILE) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedNetworkType == AlertNetworkType.WIFI,
                            onClick = { selectedNetworkType = AlertNetworkType.WIFI },
                            label = { Text("Wi-Fi") },
                            leadingIcon = if (selectedNetworkType == AlertNetworkType.WIFI) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val mb = pendingThresholdMb ?: return@TextButton
                        onIntent(
                            DashboardIntent.ConfigureAlert(
                                DataAlert(
                                    uid = selectedTargetUid,
                                    packageName = selectedTargetPackageName,
                                    thresholdBytes = mb * 1024L * 1024L,
                                    windowSeconds = 3600,
                                    triggerOnBackground = false,
                                    notificationType = AlertType.BOTH,
                                    networkType = selectedNetworkType,
                                    isEnabled = true
                                )
                            )
                        )
                        thresholdMb = ""
                        pendingThresholdMb = null
                        showNetworkTypeDialog = false
                    }
                ) {
                    Text("Enable Alert")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNetworkTypeDialog = false
                        pendingThresholdMb = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Foreground Monitoring",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = if (foregroundMonitoringEnabled) {
                                "Starts data monitoring when NetGuard Pulse launches."
                            } else {
                                "Monitoring service is stopped and will not auto-restart."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(
                        checked = foregroundMonitoringEnabled,
                        onCheckedChange = onForegroundMonitoringChanged
                    )
                }
            }
        }

        item {
            Text(
                text = "Create Daily Data Limit Alarm",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = "Triggers a high-priority ringtone/vibrator once daily usage crosses the limit. You will choose mobile data or Wi-Fi when enabling the alert.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Dropdown Selector for Target Scope
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Limit Target: $selectedOptionText",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select target"
                        )
                    }
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    options.forEach { (uid, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                selectedTargetUid = uid
                                selectedTargetPackageName = if (uid == -1) {
                                    "All Transmissions"
                                } else {
                                    state.appList.firstOrNull { it.uid == uid }?.packageName ?: "App $uid"
                                }
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = thresholdMb,
                    onValueChange = { thresholdMb = it },
                    placeholder = { Text("Megabytes (e.g. 50)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {
                        val mb = thresholdMb.toLongOrNull()
                        if (mb != null) {
                            pendingThresholdMb = mb
                            selectedNetworkType = AlertNetworkType.MOBILE
                            showNetworkTypeDialog = true
                        }
                    },
                    modifier = Modifier.height(56.dp)
                ) {
                    Text("Enable Alert")
                }
            }
        }

        item {
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        }

        item {
            Text(
                text = "Active Limits Policy (${state.dataAlerts.size})",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }

        if (state.dataAlerts.isEmpty()) {
            item {
                Text(
                    text = "No limits active. System is fully unconstrained.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            items(state.dataAlerts) { alert ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            val context = LocalContext.current
                            val alertTargetName = if (alert.uid == -1) {
                                "Overall Daily Limit"
                            } else {
                                val pkgName = alert.packageName
                                if (pkgName != null) {
                                    try {
                                        val appInfo = context.packageManager.getApplicationInfo(pkgName, 0)
                                        context.packageManager.getApplicationLabel(appInfo).toString()
                                    } catch (e: Exception) {
                                        pkgName.substringAfterLast('.')
                                    }
                                } else {
                                    "App ${alert.uid}"
                                }
                            }
                            Text(
                                text = "$alertTargetName Breach Alarm",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val networkLabel = when (alert.networkType) {
                                AlertNetworkType.MOBILE -> "Mobile data"
                                AlertNetworkType.WIFI -> "Wi-Fi"
                            }
                            val scopeText = if (alert.uid == -1) {
                                "Overall daily $networkLabel"
                            } else {
                                "App daily $networkLabel"
                            }
                            Text(
                                text = "Threshold: ${formatBytes(alert.thresholdBytes)} | $scopeText",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (alert.hasFired) {
                                Spacer(modifier = Modifier.height(4.dp))
                                AssistChip(
                                    onClick = {},
                                    enabled = false,
                                    label = { Text("Fired") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                )
                            }
                        }
                        IconButton(onClick = { onIntent(DashboardIntent.DeleteAlert(alert.id)) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete alarm",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryTabContent(
    state: DashboardUiState,
    onIntent: (DashboardIntent) -> Unit,
    animationsEnabled: Boolean = true,
) {
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    if (showStartPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.historyCustomStartMs ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val selected = pickerState.selectedDateMillis ?: return@TextButton
                    val end = state.historyCustomEndMs ?: System.currentTimeMillis()
                    onIntent(DashboardIntent.HistoryCustomRangeSelected(selected, end.coerceAtLeast(selected)))
                    showStartPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showStartPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showEndPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.historyCustomEndMs ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val selected = pickerState.selectedDateMillis ?: return@TextButton
                    val start = state.historyCustomStartMs ?: startOfDayMillis(System.currentTimeMillis())
                    onIntent(DashboardIntent.HistoryCustomRangeSelected(start, selected.coerceAtLeast(start)))
                    showEndPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showEndPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }

    val body: @Composable () -> Unit = {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Text(
                    text = "Usage History",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Mobile, Wi-Fi, and hotspot usage for the selected period",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }

            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HistoryDateRange.entries.forEach { range ->
                        FilterChip(
                            selected = state.historyDateRange == range,
                            onClick = { onIntent(DashboardIntent.HistoryDateRangeChanged(range)) },
                            label = {
                                Text(
                                    when (range) {
                                        HistoryDateRange.TODAY -> "Today"
                                        HistoryDateRange.LAST_7_DAYS -> "7 Days"
                                        HistoryDateRange.LAST_30_DAYS -> "30 Days"
                                        HistoryDateRange.CUSTOM -> "Custom"
                                    }
                                )
                            }
                        )
                    }
                }
            }

            if (state.historyDateRange == HistoryDateRange.CUSTOM) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showStartPicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "From: ${state.historyCustomStartMs?.let { dateFormat.format(Date(it)) } ?: "Pick date"}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = { showEndPicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "To: ${state.historyCustomEndMs?.let { dateFormat.format(Date(it)) } ?: "Pick date"}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UsageCategory.entries.forEach { category ->
                        FilterChip(
                            selected = state.historyCategory == category,
                            onClick = { onIntent(DashboardIntent.HistoryCategoryChanged(category)) },
                            label = {
                                Text(
                                    when (category) {
                                        UsageCategory.ALL -> "All"
                                        UsageCategory.MOBILE -> "Mobile"
                                        UsageCategory.WIFI -> "Wi-Fi"
                                        UsageCategory.HOTSPOT -> "Hotspot"
                                    }
                                )
                            }
                        )
                    }
                }
            }

            val report = state.historyReport
            if (state.historyLoading && report == null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (report != null) {
                item {
                    HistorySummaryCard(report = report, category = state.historyCategory)
                }

                if (report.buckets.isEmpty()) {
                    item {
                        Text(
                            text = "No usage recorded for this period.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                } else {
                    items(report.buckets, key = { it.startMs }) { bucket ->
                        HistoryBucketRow(bucket = bucket, category = state.historyCategory)
                    }
                }
            }
        }
    }

    if (animationsEnabled) {
        PullToRefreshBox(
            isRefreshing = state.historyLoading,
            onRefresh = { onIntent(DashboardIntent.ReloadHistory) },
            modifier = Modifier.fillMaxSize(),
        ) {
            body()
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            body()
        }
    }
}

@Composable
private fun HistorySummaryCard(
    report: UsageHistoryReport,
    category: UsageCategory
) {
    val traffic = when (category) {
        UsageCategory.MOBILE -> report.summary.mobile
        UsageCategory.WIFI -> report.summary.wifi
        UsageCategory.HOTSPOT -> report.summary.hotspot
        UsageCategory.ALL -> UsageTraffic(
            rxBytes = report.summary.mobile.rxBytes + report.summary.wifi.rxBytes + report.summary.hotspot.rxBytes,
            txBytes = report.summary.mobile.txBytes + report.summary.wifi.txBytes + report.summary.hotspot.txBytes
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = when (category) {
                    UsageCategory.ALL -> "Total Usage"
                    UsageCategory.MOBILE -> "Mobile Data"
                    UsageCategory.WIFI -> "Wi-Fi Data"
                    UsageCategory.HOTSPOT -> "Hotspot Data"
                },
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatBytes(traffic.totalBytes),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("↓ ${formatBytes(traffic.rxBytes)}", style = MaterialTheme.typography.bodyMedium)
                Text("↑ ${formatBytes(traffic.txBytes)}", style = MaterialTheme.typography.bodyMedium)
            }

            if (category == UsageCategory.ALL) {
                Spacer(modifier = Modifier.height(12.dp))
                HistoryBreakdownRow("Mobile", report.summary.mobile)
                HistoryBreakdownRow("Wi-Fi", report.summary.wifi)
                HistoryBreakdownRow("Hotspot", report.summary.hotspot)
            }
        }
    }
}

@Composable
private fun HistoryBreakdownRow(label: String, traffic: UsageTraffic) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(formatBytes(traffic.totalBytes), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun HistoryBucketRow(
    bucket: UsageHistoryBucket,
    category: UsageCategory
) {
    val traffic = bucket.totalFor(category)
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = bucket.label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatBytes(traffic.totalBytes),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "↓${formatBytes(traffic.rxBytes)}  ↑${formatBytes(traffic.txBytes)}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}

private fun startOfDayMillis(timestampMs: Long): Long {
    return java.util.Calendar.getInstance().apply {
        timeInMillis = timestampMs
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}

@Composable
private fun DeviceDataCard(
    title: String,
    totalBytes: Long,
    rxPerSec: Long,
    txPerSec: Long,
    containerColor: Color,
    contentColor: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(
                    color = contentColor.copy(alpha = 0.8f)
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatBytes(totalBytes),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Black,
                    color = contentColor
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "LIVE ↓",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = contentColor.copy(alpha = 0.7f)
                        )
                    )
                    Text(
                        text = "${formatBytes(rxPerSec)}/s",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = contentColor
                        )
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "LIVE ↑",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = contentColor.copy(alpha = 0.7f)
                        )
                    )
                    Text(
                        text = "${formatBytes(txPerSec)}/s",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = contentColor
                        )
                    )
                }
            }
        }
    }
}

// Helper methods for formatting
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1] + ""
    return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

fun formatTime(ms: Long): String {
    val date = java.util.Date(ms)
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(date)
}

private fun formatHotspotClientAddress(client: HotspotClient): String {
    val ipText = if (client.ipAddress == "0.0.0.0") "IP: resolving" else "IP: ${client.ipAddress}"
    return "$ipText | MAC: ${client.macAddress}"
}

internal fun buildHotspotSessionConfigFromInputs(
    autoOffEnabled: Boolean,
    dataLimitEnabled: Boolean,
    dataMbInput: String,
    timeLimitEnabled: Boolean,
    timeMinutesInput: String
): HotspotSessionConfig {
    val maxDataMb = Long.MAX_VALUE / (1024L * 1024L)
    val maxMinutes = Long.MAX_VALUE / 60_000L
    val dataMb = dataMbInput.toLongOrNull()?.coerceIn(1L, maxDataMb) ?: 1L
    val minutes = timeMinutesInput.toLongOrNull()?.coerceIn(1L, maxMinutes) ?: 1L

    return HotspotSessionConfig(
        autoOffEnabled = autoOffEnabled,
        dataLimitEnabled = dataLimitEnabled,
        dataLimitBytes = if (autoOffEnabled && dataLimitEnabled) {
            dataMb * 1024L * 1024L
        } else {
            Long.MAX_VALUE
        },
        timeLimitEnabled = timeLimitEnabled,
        timeLimitMs = if (autoOffEnabled && timeLimitEnabled) {
            minutes * 60_000L
        } else {
            Long.MAX_VALUE
        },
        speedLimitKbps = 0L
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotspotTabContent(
    state: DashboardUiState,
    onIntent: (DashboardIntent) -> Unit,
    animationsEnabled: Boolean = true,
) {
    var selectedClientForLimit by remember { mutableStateOf<HotspotClient?>(null) }
    var limitInput by remember { mutableStateOf("") }

    val body: @Composable () -> Unit = {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
        item {
            HotspotSessionAutoOffCard(
                config = state.hotspotSessionConfig,
                status = state.hotspotSessionStatus,
                isHotspotEnabled = state.isHotspotEnabled,
                onSave = { config ->
                    onIntent(DashboardIntent.UpdateHotspotSessionConfig(config))
                }
            )
        }

        // Hotspot status + in-app toggle
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Mobile Hotspot",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            if (state.isHotspotEnabled) Color(0xFF4CAF50) else Color(0xFFF44336),
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (state.isHotspotEnabled) "Enabled" else "Disabled",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    )
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                onIntent(DashboardIntent.OpenHotspotSettings)
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Text("Settings")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (state.isHotspotEnabled) {
                            if (state.hotspotClients.isEmpty()) {
                                "Hotspot is on — scanning for connected devices…"
                            } else {
                                "${state.hotspotClients.count { it.isConnected }} connected · ${state.hotspotClients.count { !it.isConnected }} disconnected"
                            }
                        } else {
                            "Turn on hotspot in Settings to start a monitored session."
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    )
                    if (state.hotspotClients.any { it.isBlocked }) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (state.isDnsActive) {
                                "Blocked in-app: MAC block when available, otherwise IP block (captive portal + VPN routes)."
                            } else {
                                "Blocked in-app: limits enforce by IP on this device when MAC cannot be resolved."
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                            )
                        )
                    }
                }
            }
        }

        // Active hotspot stats card
        item {
            val totalBytes = state.hotspotClients.sumOf { it.rxBytes + it.txBytes }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "DEVICES", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = "${state.hotspotClients.count { it.isConnected }}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "TOTAL TETHERED DATA", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = formatBytes(totalBytes),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "BLOCKED", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = "${state.hotspotClients.count { it.isBlocked }}",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (state.hotspotClients.any { it.isBlocked }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }
        }

        // 3. Section Title
        item {
            Text(
                text = "Tethered Clients",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // 4. Client List
        if (state.hotspotClients.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No tethered connections detected.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = "Turn on mobile hotspot and connect a device to see live usage.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(state.hotspotClients, key = { it.macAddress }) { client ->
                HotspotClientRowItem(
                    client = client,
                    isDeviceRooted = state.isDeviceRooted,
                    onSetLimitClick = {
                        selectedClientForLimit = client
                        limitInput = client.limitBytes?.let { (it / 1024L / 1024L).toString() } ?: ""
                    },
                    onToggleBlock = {
                        onIntent(DashboardIntent.SetHotspotBlocked(client.macAddress, !client.isBlocked))
                    },
                    onResetUsage = {
                        onIntent(DashboardIntent.ResetHotspotUsage(client.macAddress))
                    }
                )
            }
        }
    }
    }

    if (animationsEnabled) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { onIntent(DashboardIntent.RefreshData) },
            modifier = Modifier.fillMaxSize(),
        ) {
            body()
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            body()
        }
    }

    // Limit configuration dialog
    if (selectedClientForLimit != null) {
        val client = selectedClientForLimit!!
        AlertDialog(
            onDismissRequest = { selectedClientForLimit = null },
            title = { Text(text = "Configure Bandwidth Limit") },
            text = {
                Column {
                    Text(
                        text = "Set a data transfer limit for ${client.deviceName} (${client.macAddress}). The device will be blocked from accessing the network once this limit is exceeded.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = limitInput,
                        onValueChange = { limitInput = it },
                        label = { Text("Limit in Megabytes (MB)") },
                        placeholder = { Text("e.g. 100") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val mb = limitInput.toLongOrNull()
                        if (mb != null && mb > 0) {
                            onIntent(DashboardIntent.SetHotspotLimit(client.macAddress, mb * 1024L * 1024L))
                        }
                        selectedClientForLimit = null
                    }
                ) {
                    Text("Save Limit")
                }
            },
            dismissButton = {
                Row {
                    if (client.limitBytes != null) {
                        TextButton(
                            onClick = {
                                onIntent(DashboardIntent.SetHotspotLimit(client.macAddress, null))
                                selectedClientForLimit = null
                            }
                        ) {
                            Text("Remove Limit", color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    TextButton(onClick = { selectedClientForLimit = null }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

}

@Composable
private fun HotspotSessionAutoOffCard(
    config: HotspotSessionConfig,
    status: HotspotSessionStatus,
    isHotspotEnabled: Boolean,
    onSave: (HotspotSessionConfig) -> Unit
) {
    var sessionAlertsEnabled by remember(config) { mutableStateOf(config.autoOffEnabled) }
    var dataLimitEnabled by remember(config) { mutableStateOf(config.dataLimitEnabled) }
    var timeLimitEnabled by remember(config) { mutableStateOf(config.timeLimitEnabled) }

    var dataMbInput by remember(config) {
        mutableStateOf(
            if (config.hasDataLimit) (config.dataLimitBytes / (1024 * 1024)).toString() else "500"
        )
    }
    var timeMinutesInput by remember(config) {
        mutableStateOf(
            if (config.hasTimeLimit) (config.timeLimitMs / 60_000).toString() else "60"
        )
    }
    var nowMs by remember(status.sessionStartMs, status.isHotspotActive) {
        mutableStateOf(System.currentTimeMillis())
    }

    LaunchedEffect(status.sessionStartMs, status.isHotspotActive) {
        while (status.isHotspotActive && status.sessionStartMs > 0L) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    fun buildConfig(): HotspotSessionConfig {
        return buildHotspotSessionConfigFromInputs(
            autoOffEnabled = sessionAlertsEnabled,
            dataLimitEnabled = dataLimitEnabled,
            dataMbInput = dataMbInput,
            timeLimitEnabled = timeLimitEnabled,
            timeMinutesInput = timeMinutesInput
        )
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Session Alerts",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Send alerts when hotspot session data or time thresholds are reached. This does not turn off hotspot or mobile data.",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (isHotspotEnabled && status.isHotspotActive) {
                val liveElapsedMs = if (status.sessionStartMs > 0L) {
                    maxOf(status.elapsedMs, nowMs - status.sessionStartMs)
                } else {
                    status.elapsedMs
                }
                val dataLine = if (config.hasDataLimit) {
                    "${formatBytes(status.sessionBytesUsed)} / ${formatBytes(config.dataLimitBytes)}"
                } else {
                    formatBytes(status.sessionBytesUsed)
                }
                val timeLine = if (config.hasTimeLimit) {
                    val remaining = (config.timeLimitMs - liveElapsedMs).coerceAtLeast(0L)
                    "${formatDurationMs(liveElapsedMs)} elapsed · ${formatDurationMs(remaining)} left"
                } else {
                    "${formatDurationMs(liveElapsedMs)} elapsed"
                }
                Text(
                    text = "Session: $dataLine · $timeLine",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.9f)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(checked = sessionAlertsEnabled, onCheckedChange = { sessionAlertsEnabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Session alerts enabled", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = dataLimitEnabled, onCheckedChange = { dataLimitEnabled = it }, enabled = sessionAlertsEnabled)
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = dataMbInput,
                    onValueChange = { dataMbInput = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Data alert (MB)") },
                    enabled = sessionAlertsEnabled && dataLimitEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = timeLimitEnabled, onCheckedChange = { timeLimitEnabled = it }, enabled = sessionAlertsEnabled)
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = timeMinutesInput,
                    onValueChange = { timeMinutesInput = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Time alert (minutes)") },
                    enabled = sessionAlertsEnabled && timeLimitEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Button(
                onClick = { onSave(buildConfig()) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save session alerts")
            }
        }
    }
}

private fun formatDurationMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

@Composable
fun HotspotClientRowItem(
    client: HotspotClient,
    isDeviceRooted: Boolean,
    onSetLimitClick: () -> Unit,
    onToggleBlock: () -> Unit,
    onResetUsage: () -> Unit
) {
    val totalUsed = client.rxBytes + client.txBytes
    val limitText = client.limitBytes?.let { formatBytes(it) } ?: "No Limit"

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (client.isBlocked) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Initials Avatar
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (client.isBlocked) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.secondaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = client.deviceName.take(2).uppercase(),
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = if (client.isBlocked) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = client.deviceName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    if (client.isConnected) "CONNECTED" else "DISCONNECTED",
                                    fontSize = 10.sp
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = if (client.isConnected) {
                                    Color(0xFF4CAF50).copy(alpha = 0.16f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                labelColor = if (client.isConnected) {
                                    Color(0xFF1B5E20)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            ),
                            border = null,
                            modifier = Modifier.height(20.dp)
                        )
                        if (client.isBlocked) {
                            Spacer(modifier = Modifier.width(8.dp))
                            SuggestionChip(
                                onClick = {},
                                label = { Text("BLOCKED", fontSize = 10.sp) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    labelColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                border = null,
                                modifier = Modifier.height(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatHotspotClientAddress(client),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                // Action Menu/Buttons
                IconButton(onClick = onSetLimitClick) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Set Limit",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Data usage + live speed
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Usage: ${formatBytes(totalUsed)} / $limitText",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                    )
                    Text(
                        text = "↓ ${formatBytes(client.rxBytes)}  ↑ ${formatBytes(client.txBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    val speedSum = client.rxSpeed + client.txSpeed
                    Text(
                        text = if (!client.isConnected) {
                            "offline"
                        } else if (speedSum > 0 && !client.isBlocked) {
                            "↓ ${formatBytes(client.rxSpeed)}/s"
                        } else {
                            "idle"
                        },
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = if (client.isConnected && speedSum > 0 && !client.isBlocked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            fontWeight = FontWeight.Bold
                        )
                    )
                    if (client.isConnected && speedSum > 0 && !client.isBlocked) {
                        Text(
                            text = "↑ ${formatBytes(client.txSpeed)}/s",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            )
                        )
                    }
                }
            }

            // Progress Bar if limit is configured
            if (client.limitBytes != null) {
                Spacer(modifier = Modifier.height(8.dp))
                val progress = (totalUsed.toFloat() / client.limitBytes.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (progress >= 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Client actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onResetUsage,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset", fontSize = 12.sp)
                }
                // Blocking a client only works on rooted devices, so the Block action is
                // hidden when the device is not rooted. Unblock stays available so an
                // already-blocked client (e.g. from an exceeded limit) can be restored.
                if (isDeviceRooted || client.isBlocked) {
                    OutlinedButton(
                        onClick = onToggleBlock,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (client.isBlocked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    ) {
                        Text(
                            text = if (client.isBlocked) "Unblock" else "Block",
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageAccessBanner(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onOpenSettings),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Usage access is off",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                )
                Text(
                    text = "Tap to enable — required for data and hotspot stats.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                    )
                )
            }
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
