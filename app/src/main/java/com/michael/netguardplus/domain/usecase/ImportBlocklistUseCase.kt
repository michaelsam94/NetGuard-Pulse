package com.michael.netguardplus.domain.usecase

import com.michael.netguardplus.domain.repository.BlocklistRepository

class ImportBlocklistUseCase(
    private val blocklistRepo: BlocklistRepository
) {
    suspend operator fun invoke(content: String): Result<Int> =
        blocklistRepo.importBlocklist(content)
}
