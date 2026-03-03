package com.webhook.application

import com.webhook.domain.model.WebhookEvent
import com.webhook.infrastructure.persistence.EventRepository

sealed class EventQueryResult {
    data class Success(val event: WebhookEvent) : EventQueryResult()
    data object NotFound : EventQueryResult()
    data class Failure(val message: String) : EventQueryResult()
}

class EventQueryUseCase(private val eventRepository: EventRepository) {

    fun get(eventId: String): EventQueryResult {
        if (eventId.isBlank()) return EventQueryResult.Failure("Invalid eventId")

        return try {
            val event = eventRepository.findByEventId(eventId)
            if (event == null) EventQueryResult.NotFound else EventQueryResult.Success(event)
        } catch (e: Exception) {
            EventQueryResult.Failure("Internal error")
        }
    }
}