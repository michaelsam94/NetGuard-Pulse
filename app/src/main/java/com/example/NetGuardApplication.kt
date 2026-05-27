package com.example

import android.app.Application
import androidx.room.Room
import com.example.data.local.db.AppDatabase
import com.example.data.repository.AlertRepositoryImpl
import com.example.data.repository.BlocklistRepositoryImpl
import com.example.data.repository.DnsRepositoryImpl
import com.example.data.repository.TrafficRepositoryImpl
import com.example.domain.repository.AlertRepository
import com.example.domain.repository.BlocklistRepository
import com.example.domain.repository.DnsRepository
import com.example.domain.repository.TrafficRepository
import com.example.domain.usecase.*
import com.example.system.alert.AlertEngine
import com.example.system.dns.DnsFilterEngine
import com.example.system.stats.NetworkStatsPoller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NetGuardApplication : Application() {

    // Instantiate central AppContainer
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        
        // Start background statistics poller
        container.networkStatsPoller.startPolling(2000L)

        // Prepopulate blocklist on a background thread
        CoroutineScope(Dispatchers.IO).launch {
            container.blocklistRepository.loadBuiltinBlocklist()
        }
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

    val blocklistRepository: BlocklistRepository by lazy {
        BlocklistRepositoryImpl(
            blocklistDao = database.blocklistDao()
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
        DnsFilterEngine(blocklistRepository)
    }

    val networkStatsPoller: NetworkStatsPoller by lazy {
        NetworkStatsPoller(context, trafficRepository)
    }

    val alertEngine: AlertEngine by lazy {
        AlertEngine(context, alertRepository)
    }
}
