package com.webhook.infrastructure.persistence

import com.webhook.domain.model.EventStatus
import com.webhook.domain.model.WebhookEvent
import java.sql.Connection
import java.time.Instant

interface EventRepository {
    /**
     * 이벤트를 inbox에 삽입한다.
     * @return true = 신규 insert 성공, false = event_id 중복(멱등 처리)
     */
    fun insertIfAbsent(event: WebhookEvent): Boolean
    fun insertIfAbsent(conn: Connection, event: WebhookEvent): Boolean

    fun findByEventId(eventId: String): WebhookEvent?
    fun findByEventId(conn: Connection, eventId: String): WebhookEvent?

    /**
     * RECEIVED 상태인 이벤트를 PROCESSING으로 원자적으로 전이(선점)한다.
     * @return true = 전이 성공, false = 이미 처리 중이거나 터미널 상태(DONE/FAILED)
     */
    fun claimForProcessing(eventId: String): Boolean
    fun claimForProcessing(conn: Connection, eventId: String): Boolean

    fun updateStatus(
        eventId: String,
        status: EventStatus,
        processedAt: Instant?,
        errorMessage: String?
    ): Unit
    fun updateStatus(conn: Connection, eventId: String, status: EventStatus, processedAt: Instant?, errorMessage: String?)

}