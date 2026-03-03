package com.webhook.presentation.dto

import com.webhook.domain.model.WebhookEvent
import kotlinx.serialization.Serializable

@Serializable
data class EventResponse(
    val eventId: String,
    val eventType: String,
    val accountId: String,
    val status: String,
    val createdAt: String,
    val processedAt: String? = null,
    val errorMessage: String? = null
) {
    companion object {
        fun from(event: WebhookEvent): EventResponse = EventResponse(
            eventId      = event.eventId,
            eventType    = event.eventType.name,
            accountId    = event.accountId,
            status       = event.status.name,
            createdAt    = event.createdAt.toString(),
            processedAt  = event.processedAt?.toString(),
            errorMessage = event.errorMessage
        )
    }
}