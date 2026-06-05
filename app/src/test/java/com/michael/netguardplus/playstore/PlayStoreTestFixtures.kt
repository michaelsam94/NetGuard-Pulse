package com.michael.netguardplus.playstore

import com.michael.netguardplus.domain.model.AlertNetworkType
import com.michael.netguardplus.domain.model.AlertType
import com.michael.netguardplus.domain.model.AppTrafficInfo
import com.michael.netguardplus.domain.model.DataAlert
import com.michael.netguardplus.domain.model.DnsLogEntry
import com.michael.netguardplus.domain.model.FamilyDnsProvider
import com.michael.netguardplus.domain.model.HistoryDateRange
import com.michael.netguardplus.domain.model.HotspotClient
import com.michael.netguardplus.domain.model.HotspotSessionConfig
import com.michael.netguardplus.domain.model.HotspotSessionStatus
import com.michael.netguardplus.domain.model.TrafficSummary
import com.michael.netguardplus.domain.model.UsageCategory
import com.michael.netguardplus.domain.model.UsageHistoryBucket
import com.michael.netguardplus.domain.model.UsageHistoryReport
import com.michael.netguardplus.domain.model.UsageHistorySummary
import com.michael.netguardplus.domain.model.UsageTraffic
import com.michael.netguardplus.presentation.dashboard.DashboardUiState
import com.michael.netguardplus.presentation.dashboard.SortOrder

object PlayStoreTestFixtures {

  private const val MB = 1024L * 1024L
  private const val GB = 1024L * MB

  private val now = System.currentTimeMillis()

  val dashboardState: DashboardUiState
    get() = DashboardUiState(
      isLoading = false,
      isDnsActive = true,
      summary = TrafficSummary(
        totalRxBytes = 2_450 * MB,
        totalTxBytes = 680 * MB,
        rxPerSec = 420_000L,
        txPerSec = 95_000L,
        activeAppCount = 6,
        blockedRequestsToday = 47,
        mobileRxBytes = 1_200 * MB,
        mobileTxBytes = 310 * MB,
        mobileRxPerSec = 180_000L,
        mobileTxPerSec = 42_000L,
        wifiRxBytes = 950 * MB,
        wifiTxBytes = 280 * MB,
        wifiRxPerSec = 160_000L,
        wifiTxPerSec = 38_000L,
      ),
      appList = sampleApps(),
      dnsLogList = sampleDnsLogs(),
      selectedFamilyDnsProvider = FamilyDnsProvider.CLOUDFLARE_FAMILY,
      dataAlerts = sampleAlerts(),
      hotspotClients = sampleHotspotClients(),
      isHotspotEnabled = true,
      sortOrder = SortOrder.SPEED_DESC,
      historyReport = sampleHistoryReport(),
      historyLoading = false,
      historyDateRange = HistoryDateRange.LAST_7_DAYS,
      historyCategory = UsageCategory.ALL,
      hotspotSessionConfig = HotspotSessionConfig(
        autoOffEnabled = true,
        dataLimitEnabled = true,
        dataLimitBytes = 5 * GB,
        timeLimitEnabled = true,
        timeLimitMs = 2 * 60 * 60 * 1000L,
      ),
      hotspotSessionStatus = HotspotSessionStatus(
        isHotspotActive = true,
        sessionStartMs = now - 45 * 60 * 1000L,
        sessionBytesUsed = 890 * MB,
        elapsedMs = 45 * 60 * 1000L,
        sessionSpeedBytesPerSec = 520_000L,
      ),
    )

  val dnsState: DashboardUiState
    get() = dashboardState.copy(
      selectedFamilyDnsProvider = FamilyDnsProvider.CLOUDFLARE_FAMILY,
      isDnsActive = true,
    )

  val alertsState: DashboardUiState
    get() = dashboardState

  val hotspotState: DashboardUiState
    get() = dashboardState.copy(
      isHotspotEnabled = true,
    )

  private fun sampleApps(): List<AppTrafficInfo> = listOf(
    AppTrafficInfo(
      uid = 10101,
      packageName = "com.android.chrome",
      appLabel = "Chrome",
      rxBytesTotal = 820 * MB,
      txBytesTotal = 120 * MB,
      rxBytesPerSec = 210_000L,
      txBytesPerSec = 28_000L,
      isBackground = false,
      lastActiveMs = now,
      blockedDomains = 12,
      sessionStartMs = now - 3_600_000L,
    ),
    AppTrafficInfo(
      uid = 10102,
      packageName = "com.google.android.youtube",
      appLabel = "YouTube",
      rxBytesTotal = 640 * MB,
      txBytesTotal = 45 * MB,
      rxBytesPerSec = 95_000L,
      txBytesPerSec = 8_000L,
      isBackground = false,
      lastActiveMs = now - 30_000L,
      blockedDomains = 3,
      sessionStartMs = now - 7_200_000L,
    ),
    AppTrafficInfo(
      uid = 10103,
      packageName = "com.whatsapp",
      appLabel = "WhatsApp",
      rxBytesTotal = 210 * MB,
      txBytesTotal = 88 * MB,
      rxBytesPerSec = 42_000L,
      txBytesPerSec = 18_000L,
      isBackground = true,
      lastActiveMs = now - 120_000L,
      blockedDomains = 0,
      sessionStartMs = now - 14_400_000L,
    ),
    AppTrafficInfo(
      uid = 10104,
      packageName = "com.spotify.music",
      appLabel = "Spotify",
      rxBytesTotal = 180 * MB,
      txBytesTotal = 12 * MB,
      rxBytesPerSec = 38_000L,
      txBytesPerSec = 4_000L,
      isBackground = true,
      lastActiveMs = now - 60_000L,
      blockedDomains = 1,
      sessionStartMs = now - 5_400_000L,
    ),
  )

  private fun sampleDnsLogs(): List<DnsLogEntry> = listOf(
    DnsLogEntry(
      timestampMs = now - 120_000L,
      uid = 10101,
      packageName = "com.android.chrome",
      domain = "ads.example.net",
      queryType = "A",
      wasBlocked = true,
      resolvedIp = null,
    ),
    DnsLogEntry(
      timestampMs = now - 90_000L,
      uid = 10102,
      packageName = "com.google.android.youtube",
      domain = "youtube.com",
      queryType = "A",
      wasBlocked = false,
      resolvedIp = "142.250.80.78",
    ),
    DnsLogEntry(
      timestampMs = now - 45_000L,
      uid = 10103,
      packageName = "com.whatsapp",
      domain = "g.whatsapp.net",
      queryType = "A",
      wasBlocked = false,
      resolvedIp = "157.240.2.53",
    ),
  )

  private fun sampleAlerts(): List<DataAlert> = listOf(
    DataAlert(
      id = 1L,
      uid = -1,
      packageName = "All Transmissions",
      thresholdBytes = 2 * GB,
      windowSeconds = 86_400,
      triggerOnBackground = false,
      notificationType = AlertType.BOTH,
      networkType = AlertNetworkType.MOBILE,
      isEnabled = true,
    ),
    DataAlert(
      id = 2L,
      uid = 10102,
      packageName = "com.google.android.youtube",
      thresholdBytes = 500 * MB,
      windowSeconds = 86_400,
      triggerOnBackground = false,
      notificationType = AlertType.SOUND,
      networkType = AlertNetworkType.WIFI,
      isEnabled = true,
    ),
  )

  private fun sampleHotspotClients(): List<HotspotClient> = listOf(
    HotspotClient(
      macAddress = "AA:BB:CC:11:22:33",
      ipAddress = "192.168.43.10",
      deviceName = "Alex's Phone",
      rxBytes = 420 * MB,
      txBytes = 38 * MB,
      rxSpeed = 180_000L,
      txSpeed = 22_000L,
      limitBytes = 1 * GB,
      isBlocked = false,
      isConnected = true,
      lastSeenMs = now,
    ),
    HotspotClient(
      macAddress = "AA:BB:CC:44:55:66",
      ipAddress = "192.168.43.12",
      deviceName = "Kids Tablet",
      rxBytes = 310 * MB,
      txBytes = 18 * MB,
      rxSpeed = 95_000L,
      txSpeed = 8_000L,
      limitBytes = 500 * MB,
      isBlocked = true,
      isConnected = true,
      lastSeenMs = now - 15_000L,
    ),
    HotspotClient(
      macAddress = "AA:BB:CC:77:88:99",
      ipAddress = "192.168.43.15",
      deviceName = "Guest Laptop",
      rxBytes = 160 * MB,
      txBytes = 24 * MB,
      rxSpeed = 72_000L,
      txSpeed = 12_000L,
      limitBytes = null,
      isBlocked = false,
      isConnected = false,
      lastSeenMs = now - 5_000L,
    ),
  )

  private fun sampleHistoryReport(): UsageHistoryReport {
    val startMs = now - 7 * 86_400_000L
    return UsageHistoryReport(
      summary = UsageHistorySummary(
        mobile = UsageTraffic(rxBytes = 4_200 * MB, txBytes = 980 * MB),
        wifi = UsageTraffic(rxBytes = 6_800 * MB, txBytes = 1_400 * MB),
        hotspot = UsageTraffic(rxBytes = 2_100 * MB, txBytes = 320 * MB),
      ),
      buckets = listOf(
        UsageHistoryBucket(
          label = "Mon",
          startMs = startMs,
          endMs = startMs + 86_400_000L,
          mobile = UsageTraffic(520 * MB, 110 * MB),
          wifi = UsageTraffic(880 * MB, 190 * MB),
          hotspot = UsageTraffic(240 * MB, 40 * MB),
        ),
        UsageHistoryBucket(
          label = "Tue",
          startMs = startMs + 86_400_000L,
          endMs = startMs + 2 * 86_400_000L,
          mobile = UsageTraffic(610 * MB, 140 * MB),
          wifi = UsageTraffic(920 * MB, 210 * MB),
          hotspot = UsageTraffic(280 * MB, 45 * MB),
        ),
        UsageHistoryBucket(
          label = "Wed",
          startMs = startMs + 2 * 86_400_000L,
          endMs = startMs + 3 * 86_400_000L,
          mobile = UsageTraffic(580 * MB, 130 * MB),
          wifi = UsageTraffic(1_050 * MB, 220 * MB),
          hotspot = UsageTraffic(310 * MB, 52 * MB),
        ),
      ),
      startMs = startMs,
      endMs = now,
    )
  }
}
