package com.michael.netguardplus

import android.app.Application
import androidx.room.Room
import com.michael.netguardplus.data.local.db.AppDatabase
import com.michael.netguardplus.data.parental.ParentalControlStore
import com.michael.netguardplus.data.repository.AlertRepositoryImpl
import com.michael.netguardplus.data.repository.BlocklistRepositoryImpl
import com.michael.netguardplus.data.repository.DnsRepositoryImpl
import com.michael.netguardplus.data.repository.TrafficRepositoryImpl
import com.michael.netguardplus.domain.repository.AlertRepository
import com.michael.netguardplus.domain.repository.BlocklistRepository
import com.michael.netguardplus.domain.repository.DnsRepository
import com.michael.netguardplus.domain.repository.TrafficRepository
import com.michael.netguardplus.domain.repository.HotspotRepository
import com.michael.netguardplus.data.repository.HotspotRepositoryImpl
import com.michael.netguardplus.data.repository.DnsBlockingRepositoryImpl
import com.michael.netguardplus.domain.repository.DnsBlockingRepository
import com.michael.netguardplus.data.repository.UsageHistoryRepositoryImpl
import com.michael.netguardplus.domain.repository.UsageHistoryRepository
import com.michael.netguardplus.domain.usecase.*
import com.michael.netguardplus.system.alert.AlertEngine
import com.michael.netguardplus.system.dns.DnsFilterEngine
import com.michael.netguardplus.system.hotspot.MacAddressResolver
import com.michael.netguardplus.system.stats.NetworkStatsPoller
import com.michael.netguardplus.system.stats.TrafficMonitorService
import com.michael.netguardplus.system.hotspot.HotspotCaptivePortalService
import com.michael.netguardplus.system.vpn.LocalVpnService
import android.content.Context
import android.net.wifi.WifiManager

class NetGuardApplication : Application() {

    // Instantiate central AppContainer
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        
        if (TrafficMonitorService.isForegroundMonitoringEnabled(this)) {
            TrafficMonitorService.start(this)
        } else {
            TrafficMonitorService.stop(this)
        }
        container.hotspotRepository.startMonitoring()
    }
}

class AppContainer(private val context: Application) {

    // 1. Core SQLite Room database
    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "netguard_pulse_db"
        )
        .addMigrations(AppDatabase.MIGRATION_8_9)
        .fallbackToDestructiveMigration()
        .build()
    }

    // 2. Concrete repository implementations
    val trafficRepository: TrafficRepository by lazy {
        TrafficRepositoryImpl(
            context = context,
            trafficDao = database.trafficDao(),
            dnsLogDao = database.dnsLogDao()
        )
    }

    val dnsRepository: DnsRepository by lazy {
        DnsRepositoryImpl(
            dnsLogDao = database.dnsLogDao()
        )
    }

    val parentalControlStore: ParentalControlStore by lazy {
        ParentalControlStore(context)
    }

    val blocklistRepository: BlocklistRepository by lazy {
        BlocklistRepositoryImpl(
            blocklistDao = database.blocklistDao(),
            parentalControlStore = parentalControlStore
        )
    }

    val alertRepository: AlertRepository by lazy {
        AlertRepositoryImpl(
            alertDao = database.alertDao()
        )
    }

    // 3. Clean architecture use cases
    val getLiveTrafficUseCase: GetLiveTrafficUseCase by lazy {
        GetLiveTrafficUseCase(trafficRepository)
    }

    val checkDnsBlockUseCase: CheckDnsBlockUseCase by lazy {
        CheckDnsBlockUseCase(blocklistRepository, dnsRepository)
    }

    val importBlocklistUseCase: ImportBlocklistUseCase by lazy {
        ImportBlocklistUseCase(blocklistRepository)
    }

    val configureDataAlertUseCase: ConfigureDataAlertUseCase by lazy {
        ConfigureDataAlertUseCase(alertRepository)
    }

    val getDnsLogUseCase: GetDnsLogUseCase by lazy {
        GetDnsLogUseCase(dnsRepository)
    }

    // 4. System utilities & engines
    val dnsFilterEngine: DnsFilterEngine by lazy {
        DnsFilterEngine(blocklistRepository, parentalControlStore)
    }

    val usageHistoryRepository: UsageHistoryRepository by lazy {
        UsageHistoryRepositoryImpl(
            context = context,
            usageHistoryDao = database.usageHistoryDao()
        )
    }

    val networkStatsPoller: NetworkStatsPoller by lazy {
        NetworkStatsPoller(context, trafficRepository, usageHistoryRepository, alertEngine)
    }

    val alertEngine: AlertEngine by lazy {
        AlertEngine(context, alertRepository, trafficRepository)
    }

    val macAddressResolver: MacAddressResolver by lazy {
        MacAddressResolver(
            context,
            context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        )
    }

    val dnsBlockingRepository: DnsBlockingRepository by lazy {
        DnsBlockingRepositoryImpl(
            context = context,
            macResolver = macAddressResolver,
            onIpEnforcementChanged = { blockedIps, sessionBlocked ->
                if (blockedIps.isEmpty() && !sessionBlocked) {
                    HotspotCaptivePortalService.requestStop(context)
                    LocalVpnService.requestStopHotspotEnforcement(context)
                } else {
                    HotspotCaptivePortalService.requestSync(context)
                    LocalVpnService.applyHotspotEnforcement(context, blockedIps, sessionBlocked)
                }
            }
        )
    }

    val hotspotRepository: HotspotRepository by lazy {
        HotspotRepositoryImpl(
            context,
            database.hotspotDao(),
            usageHistoryRepository,
            parentalControlStore,
            dnsBlockingRepository
        )
    }

}
