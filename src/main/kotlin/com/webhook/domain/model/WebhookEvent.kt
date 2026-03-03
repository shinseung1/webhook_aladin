package com.webhook.domain.model

import java.time.Instant

data class WebhookEvent(
    val eventId: String,
    val eventType: EventType,
    val accountId: String,
    val rawPayload: String,
    val status: EventStatus,
    val createdAt: Instant,
    val processedAt: Instant?,
    val errorMessage: String?
)