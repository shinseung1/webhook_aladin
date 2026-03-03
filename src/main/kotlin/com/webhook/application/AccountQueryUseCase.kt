package com.webhook.application

import com.webhook.domain.model.Account
import com.webhook.infrastructure.persistence.AccountRepository

sealed class AccountQueryResult {
    data class Success(val account: Account) : AccountQueryResult()
    data object NotFound : AccountQueryResult()
    data class Failure(val message: String) : AccountQueryResult()
}

class AccountQueryUseCase(private val accountRepository: AccountRepository) {

    fun get(accountId: String): AccountQueryResult {
        if (accountId.isBlank()) return AccountQueryResult.Failure("Invalid accountId")

        return try {
            val account = accountRepository.findById(accountId)
            if (account == null) AccountQueryResult.NotFound else AccountQueryResult.Success(account)
        } catch (e: Exception) {
            AccountQueryResult.Failure("Internal error")
        }
    }
}