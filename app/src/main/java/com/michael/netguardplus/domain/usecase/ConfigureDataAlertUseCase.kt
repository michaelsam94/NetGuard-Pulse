package com.michael.netguardplus.domain.usecase

import com.michael.netguardplus.domain.model.DataAlert
import com.michael.netguardplus.domain.repository.AlertRepository

class ConfigureDataAlertUseCase(
    private val alertRepo: AlertRepository
) {
    suspend operator fun invoke(alert: DataAlert): Long =
        alertRepo.upsertAlert(alert)
}
