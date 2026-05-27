package com.example.domain.usecase

import com.example.domain.repository.BlocklistRepository

class ImportBlocklistUseCase(
    private val blocklistRepo: BlocklistRepository
) {
    suspend operator fun invoke(content: String): Result<Int> =
        blocklistRepo.importBlocklist(content)
}
