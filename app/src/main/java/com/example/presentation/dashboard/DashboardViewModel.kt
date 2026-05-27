package com.example.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.model.AppTrafficInfo
import com.example.domain.model.DataAlert
import com.example.domain.repository.AlertRepository
import com.example.domain.repository.BlocklistRepository
import com.example.domain.repository.TrafficRepository
import com.example.domain.usecase.*
import com.example.system.vpn.LocalVpnService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val getLiveTraffic: GetLiveTrafficUseCase,
    private val getDnsLog: GetDnsLogUseCase,
    private val blocklistRepo: BlocklistRepository,
    private val alertRepo: AlertRepository,
    private val trafficRepo: TrafficRepository,
    private val importBlocklistUseCase: ImportBlocklistUseCase,
    private val configureDataAlertUseCase: ConfigureDataAlertUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<DashboardEffect>()
    val effects: SharedFlow<DashboardEffect> = _effects.asSharedFlow()

    init {
        observeAllSources()
    }

    private fun observeAllSources() {
        // Observe live sorted application traffic
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

        // Observe traffic metrics summary
        viewModelScope.launch {
            trafficRepo.observeTrafficSummary()
                .catch { }
                .collect { summary ->
                    _uiState.update { it.copy(summary = summary) }
                }
        }

        // Observe custom and default blocklist domains
        viewModelScope.launch {
            blocklistRepo.observeBlockedDomains()
                .catch { }
                .collect { domains ->
                    _uiState.update { it.copy(blockedDomains = domains) }
                }
        }

        // Observe live logged DNS queries
        viewModelScope.launch {
            getDnsLog()
                .catch { }
                .collect { logs ->
                    _uiState.update { it.copy(dnsLogList = logs) }
                }
        }

        // Observe pre-configured user bandwidth usage alarms
        viewModelScope.launch {
            alertRepo.observeAlerts()
                .catch { }
                .collect { alerts ->
                    _uiState.update { it.copy(dataAlerts = alerts) }
                }
        }

        // Periodically refresh the VPN Service running state
        viewModelScope.launch {
            while (true) {
                val active = LocalVpnService.isRunning
                _uiState.update { it.copy(isVpnActive = active) }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    fun onIntent(intent: DashboardIntent) {
        viewModelScope.launch {
            when (intent) {
                is DashboardIntent.RefreshData -> {
                    // Reactive streams refresh themselves automatically
                }
                is DashboardIntent.ToggleVpn -> {
                    // Controlled by MainActivity through state
                    _uiState.update { it.copy(isVpnActive = intent.isEnabled) }
                }
                is DashboardIntent.SortChanged -> {
                    _uiState.update { state ->
                        state.copy(
                            sortOrder = intent.sort,
                            appList = sortAppList(state.appList, intent.sort)
                        )
                    }
                }
                is DashboardIntent.AddBlockedDomain -> {
                    blocklistRepo.addDomain(intent.domain)
                    _effects.emit(DashboardEffect.ShowToast("${intent.domain} added to filter pool."))
                }
                is DashboardIntent.RemoveBlockedDomain -> {
                    blocklistRepo.removeDomain(intent.domain)
                    _effects.emit(DashboardEffect.ShowToast("${intent.domain} removed."))
                }
                is DashboardIntent.ConfigureAlert -> {
                    configureDataAlertUseCase(intent.alert)
                    _effects.emit(DashboardEffect.ShowToast("Alert configuration updated."))
                }
                is DashboardIntent.DeleteAlert -> {
                    alertRepo.deleteAlert(intent.id)
                    _effects.emit(DashboardEffect.ShowToast("Alert deleted."))
                }
                is DashboardIntent.ImportBlocklist -> {
                    val res = importBlocklistUseCase(intent.text)
                    if (res.isSuccess) {
                        _effects.emit(DashboardEffect.ShowToast("Successfully imported ${res.getOrNull()} domains."))
                    } else {
                        _effects.emit(DashboardEffect.ShowToast("Import failed."))
                    }
                }
            }
        }
    }

    private fun sortAppList(list: List<AppTrafficInfo>, order: SortOrder): List<AppTrafficInfo> {
        return when (order) {
            SortOrder.SPEED_DESC -> list.sortedByDescending { it.rxBytesPerSec + it.txBytesPerSec }
            SortOrder.DATA_DESC -> list.sortedByDescending { it.rxBytesTotal + it.txBytesTotal }
            SortOrder.NAME_ASC -> list.sortedBy { it.appLabel.lowercase() }
        }
    }

    /**
     * Boilerplate Factory interface for easy manual injection
     */
    class Factory(
        private val getLiveTraffic: GetLiveTrafficUseCase,
        private val getDnsLog: GetDnsLogUseCase,
        private val blocklistRepo: BlocklistRepository,
        private val alertRepo: AlertRepository,
        private val trafficRepo: TrafficRepository,
        private val importBlocklistUseCase: ImportBlocklistUseCase,
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
                importBlocklistUseCase,
                configureDataAlertUseCase
            ) as T
        }
    }
}
