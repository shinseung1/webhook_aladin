package com.webhook.presentation.dto

import com.webhook.domain.model.Account
import kotlinx.serialization.Serializable

@Serializable
data class AccountResponse(
    val accountId: String,
    val name: String,
    val email: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String
) {
    companion object {
        fun from(account: Account): AccountResponse = AccountResponse(
            accountId = account.accountId,
            name      = account.name,
            email     = account.email,
            status    = account.status.name,
            createdAt = account.createdAt.toString(),
            updatedAt = account.updatedAt.toString()
        )
    }
}