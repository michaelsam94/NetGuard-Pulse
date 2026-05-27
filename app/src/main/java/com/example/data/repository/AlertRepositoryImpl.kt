package com.example.data.repository

import com.example.data.local.db.dao.AlertDao
import com.example.data.mapper.toDomain
import com.example.data.mapper.toEntity
import com.example.domain.model.DataAlert
import com.example.domain.repository.AlertRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AlertRepositoryImpl(
    private val alertDao: AlertDao
) : AlertRepository {

    override fun observeAlerts(): Flow<List<DataAlert>> {
        return alertDao.observeAll().map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun upsertAlert(alert: DataAlert): Long {
        return alertDao.insertOrUpdate(alert.toEntity())
    }

    override suspend fun deleteAlert(id: Long) {
        alertDao.deleteById(id)
    }

    override suspend fun getActiveAlerts(): List<DataAlert> {
        return alertDao.getActiveAlerts().map { it.toDomain() }
    }
}
