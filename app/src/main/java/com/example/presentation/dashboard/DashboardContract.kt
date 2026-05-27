package com.example.presentation.dashboard

import com.example.domain.model.AppTrafficInfo
import com.example.domain.model.DnsLogEntry
import com.example.domain.model.DataAlert
import com.example.domain.model.TrafficSummary

enum class SortOrder { SPEED_DESC, DATA_DESC, NAME_ASC }

sealed interface DashboardIntent {
    object RefreshData : DashboardIntent
    data class ToggleVpn(val isEnabled: Boolean) : DashboardIntent
    data class SortChanged(val sort: SortOrder) : DashboardIntent
    data class AddBlockedDomain(val domain: String) : DashboardIntent
    data class RemoveBlockedDomain(val domain: String) : DashboardIntent
    data class ConfigureAlert(val alert: DataAlert) : DashboardIntent
    data class DeleteAlert(val id: Long) : DashboardIntent
    data class ImportBlocklist(val text: String) : DashboardIntent
}

data class DashboardUiState(
    val isLoading: Boolean = true,
    val isVpnActive: Boolean = false,
    val summary: TrafficSummary = TrafficSummary.EMPTY,
    val appList: List<AppTrafficInfo> = emptyList(),
    val dnsLogList: List<DnsLogEntry> = emptyList(),
    val blockedDomains: Set<String> = emptySet(),
    val dataAlerts: List<DataAlert> = emptyList(),
    val sortOrder: SortOrder = SortOrder.SPEED_DESC,
    val errorMessage: String? = null
)

sealed interface DashboardEffect {
    data class ShowToast(val message: String) : DashboardEffect
}
