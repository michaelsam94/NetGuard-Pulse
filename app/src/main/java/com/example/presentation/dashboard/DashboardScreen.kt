package com.example.presentation.dashboard

import android.widget.Space
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
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
import com.example.domain.model.AppTrafficInfo
import com.example.domain.model.DnsLogEntry
import com.example.domain.model.DataAlert
import com.example.domain.model.AlertType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
    onToggleVpnService: (Boolean) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("Overview", "DNS Log", "Blocklist", "Alerts")

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
                    // Quick VPN Switch state visual anchor
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(
                            text = if (state.isVpnActive) "FIREWALL ACTIVE" else "FIREWALL DOWN",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = if (state.isVpnActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = state.isVpnActive,
                            onCheckedChange = {
                                viewModel.onIntent(DashboardIntent.ToggleVpn(it))
                                onToggleVpnService(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
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
            // Theme-safe dynamic Tab row
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                contentColor = MaterialTheme.colorScheme.primary
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

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(tween(220)) togetherWith fadeOut(tween(220))
                },
                label = "TabContentTransition"
            ) { targetTab ->
                when (targetTab) {
                    0 -> OverviewTabContent(
                        state = state,
                        onIntent = viewModel::onIntent
                    )
                    1 -> DnsLogTabContent(
                        state = state,
                        onIntent = viewModel::onIntent
                    )
                    2 -> BlocklistTabContent(
                        state = state,
                        onIntent = viewModel::onIntent
                    )
                    3 -> AlertsTabContent(
                        state = state,
                        onIntent = viewModel::onIntent
                    )
                }
            }
        }
    }
}

@Composable
fun OverviewTabContent(
    state: DashboardUiState,
    onIntent: (DashboardIntent) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            // 1. Dashboard live summary card
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
                        text = "↓ ${formatBytes(state.summary.totalRxBytes + state.summary.totalTxBytes)}",
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
                                text = "LIVE DOWNLOAD",
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
                                text = "LIVE UPLOAD",
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
            // Bandwidth sorting choices row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Application Traffic (Delta Math)",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    )
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = state.sortOrder == SortOrder.SPEED_DESC,
                        onClick = { onIntent(DashboardIntent.SortChanged(SortOrder.SPEED_DESC)) },
                        label = { Text("Speed") }
                    )
                    FilterChip(
                        selected = state.sortOrder == SortOrder.DATA_DESC,
                        onClick = { onIntent(DashboardIntent.SortChanged(SortOrder.DATA_DESC)) },
                        label = { Text("Total") }
                    )
                    FilterChip(
                        selected = state.sortOrder == SortOrder.NAME_ASC,
                        onClick = { onIntent(DashboardIntent.SortChanged(SortOrder.NAME_ASC)) },
                        label = { Text("Name") }
                    )
                }
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
                            text = "No active socket transmissions detected.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = "Stats will stream automatically upon packet flow.",
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
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary)
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

                        // Inverse Filter toggle action
                        IconButton(
                            onClick = {
                                if (log.wasBlocked) {
                                    onIntent(DashboardIntent.RemoveBlockedDomain(log.domain))
                                } else {
                                    onIntent(DashboardIntent.AddBlockedDomain(log.domain))
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (log.wasBlocked) Icons.Default.Add else Icons.Default.Delete,
                                contentDescription = "Add or Remove filter rules",
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BlocklistTabContent(
    state: DashboardUiState,
    onIntent: (DashboardIntent) -> Unit
) {
    var newDomain by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text(
                text = "Add Custom Filter Rule",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newDomain,
                    onValueChange = { newDomain = it },
                    placeholder = { Text("e.g. tracking.provider.com") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (newDomain.isNotBlank()) {
                            onIntent(DashboardIntent.AddBlockedDomain(newDomain))
                            newDomain = ""
                        }
                    },
                    modifier = Modifier.height(56.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add rule")
                }
            }
        }

        item {
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        }

        item {
            Text(
                text = "Bulk Hostlist Importer",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = "Paste standard ad-hosts/dns filter text pools directly.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = importText,
                onValueChange = { importText = it },
                placeholder = { Text("doubleclick.net\nanalytics.org\nbadsite.com") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = {
                    if (importText.isNotBlank()) {
                        onIntent(DashboardIntent.ImportBlocklist(importText))
                        importText = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Bulk Import DNS Pool")
            }
        }

        item {
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        }

        item {
            Text(
                text = "Currently Blocked Host Pools (${state.blockedDomains.size})",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }

        val list = state.blockedDomains.toList()
        if (list.isEmpty()) {
            item {
                Text(
                    text = "No filters active. Built-in list will default load automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            items(list) { domain ->
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = domain,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        IconButton(onClick = { onIntent(DashboardIntent.RemoveBlockedDomain(domain)) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Remove rule",
                                tint = MaterialTheme.colorScheme.error
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
    onIntent: (DashboardIntent) -> Unit
) {
    var thresholdMb by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text(
                text = "Create Bandwidth usage limit alarm",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = "Triggers high-priority ringtone/vibrator once a chosen limit is crossed.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
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
                            onIntent(
                                DashboardIntent.ConfigureAlert(
                                    DataAlert(
                                        uid = -1, // UID -1 applies programmatically to all
                                        packageName = "All Transmissions",
                                        thresholdBytes = mb * 1024L * 1024L,
                                        windowSeconds = 3600,
                                        triggerOnBackground = false,
                                        notificationType = AlertType.BOTH,
                                        isEnabled = true
                                    )
                                )
                            )
                            thresholdMb = ""
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
                            Text(
                                text = "Bandwidth Limit Breach Alarm",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Threshold: ${formatBytes(alert.thresholdBytes)} | System-wide scope",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
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
