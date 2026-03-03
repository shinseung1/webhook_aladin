package com.webhook.domain.service

import com.webhook.domain.model.Account
import com.webhook.domain.model.AccountStatus
import com.webhook.domain.model.EventType

class AccountBusinessException(message: String) : RuntimeException(message)

class AccountDomainService {
    fun validate(eventType: EventType, existing: Account?) {
        when (eventType) {
            EventType.ACCOUNT_CREATED -> {
                if (existing != null) throw AccountBusinessException("Account already exists")
            }
            EventType.ACCOUNT_UPDATED -> {
                if (existing == null) throw AccountBusinessException("Account not found")
                if (existing.status == AccountStatus.DELETED) throw AccountBusinessException("Account is deleted")
            }
            EventType.ACCOUNT_DELETED -> {
                if (existing == null) throw AccountBusinessException("Account not found")
                if (existing.status == AccountStatus.DELETED) throw AccountBusinessException("Account is already deleted")
            }
            EventType.EMAIL_FORWARDING_CHANGED -> {
                if (existing == null) {
                    throw WebhookBusinessException("Account not found")
                }
                if (existing.status == AccountStatus.DELETED ||
                    existing.status == AccountStatus.APPLE_DELETED
                ) {
                    throw WebhookBusinessException("Cannot change email for inactive account")
                }
            }
            EventType.APPLE_ACCOUNT_DELETED -> {
                if (existing == null) {
                    throw WebhookBusinessException("Account not found")
                }
                if (existing.status == AccountStatus.APPLE_DELETED) {
                    throw WebhookBusinessException("Account already Apple-deleted")
                }
            }
        }
    }
}