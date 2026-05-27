package com.example.domain.usecase

import com.example.domain.model.DataAlert
import com.example.domain.repository.AlertRepository

class ConfigureDataAlertUseCase(
    private val alertRepo: AlertRepository
) {
    suspend operator fun invoke(alert: DataAlert): Long =
        alertRepo.upsertAlert(alert)
}
