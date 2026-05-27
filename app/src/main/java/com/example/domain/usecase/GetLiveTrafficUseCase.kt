package com.example.domain.usecase

import com.example.domain.model.AppTrafficInfo
import com.example.domain.repository.TrafficRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class GetLiveTrafficUseCase(
    private val trafficRepo: TrafficRepository
) {
    operator fun invoke(): Flow<List<AppTrafficInfo>> =
        trafficRepo.observeAllAppTraffic()
            .map { list -> list.sortedByDescending { it.rxBytesPerSec + it.txBytesPerSec } }
            .flowOn(Dispatchers.Default)   // sort off the UI thread
}
