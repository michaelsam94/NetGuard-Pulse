package com.michael.netguardplus.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.michael.netguardplus.domain.model.AppTrafficInfo
import com.michael.netguardplus.domain.model.DataAlert
import com.michael.netguardplus.domain.repository.AlertRepository
import com.michael.netguardplus.domain.repository.BlocklistRepository
import com.michael.netguardplus.domain.repository.TrafficRepository
import com.michael.netguardplus.domain.repository.HotspotRepository
import com.michael.netguardplus.domain.repository.DnsBlockingRepository
import com.michael.netguardplus.domain.repository.UsageHistoryRepository
import com.michael.netguardplus.domain.model.HistoryDateRange
import com.michael.netguardplus.domain.model.UsageCategory
import com.michael.netguardplus.domain.model.DnsBlockingState
import com.michael.netguardplus.domain.model.FamilyDnsProvider
import com.michael.netguardplus.domain.usecase.*
import com.michael.netguardplus.system.vpn.LocalVpnService
import com.michael.netguardplus.system.hotspot.RootChecker
import com.michael.netguardplus.system.stats.NetworkStatsPoller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.Calendar

class DashboardViewModel(
    private val getLiveTraffic: GetLiveTrafficUseCase,
    private val getDnsLog: GetDnsLogUseCase,
    private val blocklistRepo: BlocklistRepository,
    private val alertRepo: AlertRepository,
    private val trafficRepo: TrafficRepository,
    private val hotspotRepo: HotspotRepository,
    private val dnsBlockingRepository: DnsBlockingRepository,
    private val usageHistoryRepo: UsageHistoryRepository,
    private val networkStatsPoller: NetworkStatsPoller,
    private val configureDataAlertUseCase: ConfigureDataAlertUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<DashboardEffect>()
    val effects: SharedFlow<DashboardEffect> = _effects.asSharedFlow()

    init {
        observeAllSources()
        loadHistory()
        checkRootStatus()
    }

    private fun checkRootStatus() {
        viewModelScope.launch {
            val rooted = withContext(Dispatchers.IO) { RootChecker.isRooted() }
            _uiState.update { it.copy(isDeviceRooted = rooted) }
        }
    }

    private fun observeAllSources() {
        viewModelScope.launch {
            getLiveTraffic()
                .catch { e -> _uiState.update { it.copy(errorMessage = e.message) } }
                .collect { apps ->
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            appList = sortAppList(apps, state.sortOrder)
                        )
                    }
                }
        }

        viewModelScope.launch {
            trafficRepo.observeTrafficSummary()
                .catch { }
                .collect { summary ->
                    _uiState.update { it.copy(summary = summary) }
                }
        }

        viewModelScope.launch {
            blocklistRepo.observeFamilyDnsProvider()
                .catch { }
                .collect { provider ->
                    _uiState.update { it.copy(selectedFamilyDnsProvider = provider) }
                }
        }

        viewModelScope.launch {
            getDnsLog()
                .catch { }
                .collect { logs ->
                    _uiState.update { it.copy(dnsLogList = logs) }
                }
        }

        viewModelScope.launch {
            alertRepo.observeAlerts()
                .catch { }
                .collect { alerts ->
                    _uiState.update { it.copy(dataAlerts = alerts) }
                }
        }

        viewModelScope.launch {
            while (true) {
                _uiState.update { it.copy(isDnsActive = LocalVpnService.isRunning) }
                delay(1000)
            }
        }

        viewModelScope.launch {
            hotspotRepo.observeAllClients()
                .catch { }
                .collect { clients ->
                    _uiState.update { it.copy(hotspotClients = clients) }
                }
        }

        viewModelScope.launch {
            hotspotRepo.observeHotspotEnabled()
                .catch { }
                .collect { enabled ->
                    _uiState.update { it.copy(isHotspotEnabled = enabled) }
                }
        }

        viewModelScope.launch {
            hotspotRepo.observeSessionConfig()
                .catch { }
                .collect { config ->
                    _uiState.update { it.copy(hotspotSessionConfig = config) }
                }
        }

        viewModelScope.launch {
            hotspotRepo.observeSessionStatus()
                .catch { }
                .collect { status ->
                    _uiState.update { it.copy(hotspotSessionStatus = status) }
                }
        }

        viewModelScope.launch {
            dnsBlockingRepository.blockedClients
                .catch { }
                .collect { blocked ->
                    _uiState.update { it.copy(dnsBlockedClientIps = blocked) }
                }
        }

        viewModelScope.launch {
            dnsBlockingRepository.blockingState
                .catch { }
                .collect { state ->
                    if (state is DnsBlockingState.Error) {
                        _effects.emit(DashboardEffect.ShowToast(state.message))
                    }
                }
        }
    }

    fun onIntent(intent: DashboardIntent) {
        viewModelScope.launch {
            when (intent) {
                is DashboardIntent.RefreshData -> {
                    _uiState.update { it.copy(isRefreshing = true) }
                    try {
                        networkStatsPoller.pollNow()
                        hotspotRepo.refreshNow()
                    } catch (e: Exception) {
                        _effects.emit(DashboardEffect.ShowToast("Refresh failed"))
                    } finally {
                        delay(400)
                        _uiState.update { it.copy(isRefreshing = false) }
                    }
                }
                is DashboardIntent.SortChanged -> {
                    _uiState.update { state ->
                        state.copy(
                            sortOrder = intent.sort,
                            appList = sortAppList(state.appList, intent.sort)
                        )
                    }
                }
                is DashboardIntent.ConfigureAlert -> {
                    configureDataAlertUseCase(intent.alert)
                    if (intent.alert.isEnabled) {
                        _effects.emit(DashboardEffect.EnsureAlertMonitoring)
                    }
                    _effects.emit(DashboardEffect.ShowToast("Alert configuration updated."))
                }
                is DashboardIntent.DeleteAlert -> {
                    alertRepo.deleteAlert(intent.id)
                    _effects.emit(DashboardEffect.ShowToast("Alert deleted."))
                }
                is DashboardIntent.SelectFamilyDnsProvider -> {
                    blocklistRepo.setFamilyDnsProvider(intent.provider)
                    _effects.emit(DashboardEffect.ApplyDnsProvider(intent.provider))
                }
                is DashboardIntent.SetHotspotLimit -> {
                    hotspotRepo.setClientLimit(intent.macAddress, intent.limitBytes)
                    val limitStr = intent.limitBytes?.let { "${it / 1024L / 1024L} MB" } ?: "removed"
                    _effects.emit(DashboardEffect.ShowToast("Limit for ${intent.macAddress} set to $limitStr."))
                }
                is DashboardIntent.SetHotspotBlocked -> {
                    if (intent.isBlocked) {
                        // Blocking a device requires root — check before proceeding
                        val rooted = withContext(Dispatchers.IO) { RootChecker.isRooted() }
                        if (!rooted) {
                            _effects.emit(
                                DashboardEffect.ShowToast(
                                    "Blocking a device requires root access. This device is not rooted."
                                )
                            )
                            return@launch
                        }
                    }
                    hotspotRepo.setClientBlocked(intent.macAddress, intent.isBlocked)
                    if (intent.isBlocked) {
                        _effects.emit(
                            DashboardEffect.ShowToast(
                                "Device blocked — Wi‑Fi access restricted for this client."
                            )
                        )
                    } else {
                        _effects.emit(DashboardEffect.ShowToast("Device unblocked."))
                    }
                }
                is DashboardIntent.ResetHotspotUsage -> {
                    hotspotRepo.resetClientUsage(intent.macAddress)
                    _effects.emit(
                        DashboardEffect.ShowToast(
                            "Usage reset — client unblocked. Reconnect to hotspot if browsing does not resume."
                        )
                    )
                }
                is DashboardIntent.UnblockHotspotClient -> {
                    dnsBlockingRepository.unblockClient(intent.ipAddress)
                    val client = _uiState.value.hotspotClients.find { it.ipAddress == intent.ipAddress }
                    if (client != null) {
                        hotspotRepo.setClientBlocked(client.macAddress, false)
                    }
                    _effects.emit(DashboardEffect.ShowToast("Client ${intent.ipAddress} unblocked."))
                }
                is DashboardIntent.ToggleHotspot -> {
                    val result = hotspotRepo.setHotspotEnabled(intent.enabled)
                    if (result.isSuccess) {
                        val status = if (intent.enabled) "Hotspot enabled" else "Hotspot disabled"
                        _effects.emit(DashboardEffect.ShowToast(status))
                    } else {
                        _effects.emit(
                            DashboardEffect.ShowToast(
                                result.exceptionOrNull()?.message ?: "Could not change hotspot state"
                            )
                        )
                    }
                }
                is DashboardIntent.OpenHotspotSettings -> {
                    hotspotRepo.openHotspotSettings()
                }
                is DashboardIntent.HistoryDateRangeChanged -> {
                    _uiState.update { it.copy(historyDateRange = intent.range) }
                    loadHistory()
                }
                is DashboardIntent.HistoryCategoryChanged -> {
                    _uiState.update { it.copy(historyCategory = intent.category) }
                }
                is DashboardIntent.HistoryCustomRangeSelected -> {
                    _uiState.update {
                        it.copy(
                            historyDateRange = HistoryDateRange.CUSTOM,
                            historyCustomStartMs = intent.startMs,
                            historyCustomEndMs = intent.endMs
                        )
                    }
                    loadHistory()
                }
                is DashboardIntent.ReloadHistory -> loadHistory()
                is DashboardIntent.UpdateHotspotSessionConfig -> {
                    hotspotRepo.updateSessionConfig(intent.config)
                    _effects.emit(DashboardEffect.ShowToast("Session limits saved"))
                }
            }
        }
    }

    private fun loadHistory() {
        viewModelScope.launch {
            _uiState.update { it.copy(historyLoading = true) }
            try {
                val state = _uiState.value
                val (startMs, endMs) = resolveHistoryRange(
                    state.historyDateRange,
                    state.historyCustomStartMs,
                    state.historyCustomEndMs
                )
                val report = usageHistoryRepo.loadHistory(startMs, endMs)
                _uiState.update { it.copy(historyReport = report, historyLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(historyLoading = false, errorMessage = e.message) }
            }
        }
    }

    private fun resolveHistoryRange(
        range: HistoryDateRange,
        customStartMs: Long?,
        customEndMs: Long?
    ): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        return when (range) {
            HistoryDateRange.TODAY -> startOfDayMs(now) to now
            HistoryDateRange.LAST_7_DAYS -> (now - 7 * DAY_MS) to now
            HistoryDateRange.LAST_30_DAYS -> (now - 30 * DAY_MS) to now
            HistoryDateRange.CUSTOM -> {
                val start = customStartMs ?: startOfDayMs(now)
                val end = (customEndMs ?: now).coerceAtLeast(start)
                start to end
            }
        }
    }

    private fun startOfDayMs(timestampMs: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timestampMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    companion object {
        private const val DAY_MS = 86_400_000L
    }

    private fun sortAppList(list: List<AppTrafficInfo>, order: SortOrder): List<AppTrafficInfo> {
        return when (order) {
            SortOrder.SPEED_DESC -> list.sortedByDescending { it.rxBytesPerSec + it.txBytesPerSec }
            SortOrder.DATA_DESC -> list.sortedByDescending { it.rxBytesTotal + it.txBytesTotal }
            SortOrder.NAME_ASC -> list.sortedBy { it.appLabel.lowercase() }
        }
    }

    class Factory(
        private val getLiveTraffic: GetLiveTrafficUseCase,
        private val getDnsLog: GetDnsLogUseCase,
        private val blocklistRepo: BlocklistRepository,
        private val alertRepo: AlertRepository,
        private val trafficRepo: TrafficRepository,
        private val hotspotRepo: HotspotRepository,
        private val dnsBlockingRepository: DnsBlockingRepository,
        private val usageHistoryRepo: UsageHistoryRepository,
        private val networkStatsPoller: NetworkStatsPoller,
        private val configureDataAlertUseCase: ConfigureDataAlertUseCase
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DashboardViewModel(
                getLiveTraffic,
                getDnsLog,
                blocklistRepo,
                alertRepo,
                trafficRepo,
                hotspotRepo,
                dnsBlockingRepository,
                usageHistoryRepo,
                networkStatsPoller,
                configureDataAlertUseCase
            ) as T
        }
    }
}
