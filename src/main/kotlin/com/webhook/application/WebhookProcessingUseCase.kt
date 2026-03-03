package com.webhook.application

import com.webhook.domain.model.EventStatus
import com.webhook.domain.model.EventType
import com.webhook.domain.model.WebhookEvent
import com.webhook.domain.service.WebhookBusinessException
import com.webhook.infrastructure.persistence.AccountRepository
import com.webhook.infrastructure.persistence.DatabaseFactory
import com.webhook.infrastructure.persistence.EventRepository
import java.time.Instant

data class ProcessWebhookCommand(
    val eventId: String,
    val eventType: EventType,
    val accountId: String,
    val rawPayload: String,
    val occurredAt: Instant
)

data class ProcessWebhookResult(
    val eventId: String,
    val status: EventStatus,
    val errorMessage: String? = null,
    val isDuplicate: Boolean = false
)

class WebhookProcessingUseCase(
    private val eventRepository: EventRepository,
    private val accountRepository: AccountRepository
) {

    fun process(cmd: ProcessWebhookCommand): ProcessWebhookResult =
        DatabaseFactory.transaction { conn ->
            val now = Instant.now()

            if (cmd.eventType == EventType.ACCOUNT_CREATED || cmd.eventType == EventType.ACCOUNT_UPDATED) {
                accountRepository.upsert(cmd.accountId, cmd.rawPayload, cmd.occurredAt)
            }

            val receivedEvent = WebhookEvent(
                eventId = cmd.eventId,
                eventType = cmd.eventType,
                accountId = cmd.accountId,
                rawPayload = cmd.rawPayload,
                status = EventStatus.RECEIVED,
                createdAt = now,
                processedAt = null,
                errorMessage = null
            )

            val isNew = eventRepository.insertIfAbsent(conn, receivedEvent)
            if (!isNew) {
                val existing = eventRepository.findByEventId(conn, cmd.eventId)
                    ?: return@transaction ProcessWebhookResult(
                        eventId = cmd.eventId,
                        status = EventStatus.FAILED,
                        errorMessage = "Event not found after duplicate detection",
                        isDuplicate = true
                    )

                return@transaction ProcessWebhookResult(
                    eventId = existing.eventId,
                    status = existing.status,
                    errorMessage = existing.errorMessage,
                    isDuplicate = true
                )
            }

            val claimed = eventRepository.claimForProcessing(conn, cmd.eventId)
            if (!claimed) {
                val existing = eventRepository.findByEventId(conn, cmd.eventId)
                    ?: return@transaction ProcessWebhookResult(
                        eventId = cmd.eventId,
                        status = EventStatus.FAILED,
                        errorMessage = "Event not found after claim failure",
                        isDuplicate = true
                    )

                return@transaction ProcessWebhookResult(
                    eventId = existing.eventId,
                    status = existing.status,
                    errorMessage = existing.errorMessage,
                    isDuplicate = true
                )
            }

            val supported = setOf(
                EventType.EMAIL_FORWARDING_CHANGED,
                EventType.ACCOUNT_DELETED,
                EventType.APPLE_ACCOUNT_DELETED
            )

            if (cmd.eventType !in supported) {
                val processedAt = Instant.now()
                eventRepository.updateStatus(cmd.eventId, EventStatus.FAILED, processedAt, "Unsupported event type: ${cmd.eventType.name}")
                return@transaction ProcessWebhookResult(cmd.eventId, EventStatus.FAILED, "Unsupported event type: ${cmd.eventType.name}")
            }

            try {
                when (cmd.eventType) {
                    EventType.ACCOUNT_CREATED,
                    EventType.ACCOUNT_UPDATED -> {
                        accountRepository.upsert(conn, cmd.accountId, cmd.rawPayload, cmd.occurredAt)
                    }
                    EventType.ACCOUNT_DELETED -> {
                        accountRepository.deleteOrClose(conn, cmd.accountId, cmd.occurredAt)
                    }
                    EventType.EMAIL_FORWARDING_CHANGED -> {
                        accountRepository.updateEmailForwarding(conn, cmd.accountId, cmd.rawPayload, cmd.occurredAt)
                    }
                    EventType.APPLE_ACCOUNT_DELETED -> {
                        accountRepository.markAppleDeleted(conn, cmd.accountId, cmd.occurredAt)
                    }
                }

                eventRepository.updateStatus(conn, cmd.eventId, EventStatus.DONE, Instant.now(), null)
                ProcessWebhookResult(eventId = cmd.eventId, status = EventStatus.DONE)
            } catch (e: WebhookBusinessException) {
                val processedAt = Instant.now()
                eventRepository.updateStatus(conn, cmd.eventId, EventStatus.FAILED, processedAt, e.message)
                ProcessWebhookResult(eventId = cmd.eventId, status = EventStatus.FAILED, errorMessage = e.message)
            }
        }
}