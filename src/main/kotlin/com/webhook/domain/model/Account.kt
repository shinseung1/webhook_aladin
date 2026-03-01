package com.webhook.domain.model

import java.time.Instant

data class Account(
    val accountId: String,
    val name: String,
    val email: String,
    val status: AccountStatus,
    val createdAt: Instant,
    val updatedAt: Instant
)