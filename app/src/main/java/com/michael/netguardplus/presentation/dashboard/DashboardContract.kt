package com.michael.netguardplus.presentation.dashboard

import com.michael.netguardplus.domain.model.AppTrafficInfo
import com.michael.netguardplus.domain.model.DnsLogEntry
import com.michael.netguardplus.domain.model.DataAlert
import com.michael.netguardplus.domain.model.TrafficSummary
import com.michael.netguardplus.domain.model.HotspotClient
import com.michael.netguardplus.domain.model.HotspotSessionConfig
import com.michael.netguardplus.domain.model.HotspotSessionStatus
import com.michael.netguardplus.domain.model.HistoryDateRange
import com.michael.netguardplus.domain.model.UsageCategory
import com.michael.netguardplus.domain.model.UsageHistoryReport
import com.michael.netguardplus.domain.model.FamilyDnsProvider

enum class SortOrder { SPEED_DESC, DATA_DESC, NAME_ASC }

sealed interface DashboardIntent {
    object RefreshData : DashboardIntent
    data class SortChanged(val sort: SortOrder) : DashboardIntent
    data class ConfigureAlert(val alert: DataAlert) : DashboardIntent
    data class DeleteAlert(val id: Long) : DashboardIntent
    data class SelectFamilyDnsProvider(val provider: FamilyDnsProvider) : DashboardIntent
    data class SetHotspotLimit(val macAddress: String, val limitBytes: Long?) : DashboardIntent
    data class SetHotspotBlocked(val macAddress: String, val isBlocked: Boolean) : DashboardIntent
    data class ResetHotspotUsage(val macAddress: String) : DashboardIntent
    data class UnblockHotspotClient(val ipAddress: String) : DashboardIntent
    data class ToggleHotspot(val enabled: Boolean) : DashboardIntent
    object OpenHotspotSettings : DashboardIntent
    data class HistoryDateRangeChanged(val range: HistoryDateRange) : DashboardIntent
    data class HistoryCategoryChanged(val category: UsageCategory) : DashboardIntent
    data class HistoryCustomRangeSelected(val startMs: Long, val endMs: Long) : DashboardIntent
    object ReloadHistory : DashboardIntent
    data class UpdateHotspotSessionConfig(val config: HotspotSessionConfig) : DashboardIntent
}

data class DashboardUiState(
    val isLoading: Boolean = true,
    val isDnsActive: Boolean = false,
    val summary: TrafficSummary = TrafficSummary.EMPTY,
    val appList: List<AppTrafficInfo> = emptyList(),
    val dnsLogList: List<DnsLogEntry> = emptyList(),
    val selectedFamilyDnsProvider: FamilyDnsProvider = FamilyDnsProvider.SYSTEM_DEFAULT,
    val dataAlerts: List<DataAlert> = emptyList(),
    val hotspotClients: List<HotspotClient> = emptyList(),
    val dnsBlockedClientIps: Set<String> = emptySet(),
    val isHotspotEnabled: Boolean = false,
    val isDeviceRooted: Boolean = false,
    val sortOrder: SortOrder = SortOrder.SPEED_DESC,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val historyReport: UsageHistoryReport? = null,
    val historyLoading: Boolean = false,
    val historyDateRange: HistoryDateRange = HistoryDateRange.TODAY,
    val historyCategory: UsageCategory = UsageCategory.ALL,
    val historyCustomStartMs: Long? = null,
    val historyCustomEndMs: Long? = null,
    val hotspotSessionConfig: HotspotSessionConfig = HotspotSessionConfig(),
    val hotspotSessionStatus: HotspotSessionStatus = HotspotSessionStatus()
)

sealed interface DashboardEffect {
    data class ShowToast(val message: String) : DashboardEffect
    data class ApplyDnsProvider(val provider: FamilyDnsProvider) : DashboardEffect
    object EnsureAlertMonitoring : DashboardEffect
}
