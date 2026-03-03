package com.webhook.domain.service

import com.webhook.domain.model.EventStatus

object EventStateMachine {

    private val allowedTransitions: Map<EventStatus, Set<EventStatus>> = mapOf(
        EventStatus.RECEIVED   to setOf(EventStatus.PROCESSING),
        EventStatus.PROCESSING to setOf(EventStatus.DONE, EventStatus.FAILED),
        EventStatus.DONE       to emptySet(),
        EventStatus.FAILED     to emptySet()
    )

    fun canTransition(from: EventStatus, to: EventStatus): Boolean =
        allowedTransitions[from]?.contains(to) == true

    fun requireTransition(from: EventStatus, to: EventStatus) {
        if (!canTransition(from, to)) {
            throw InvalidStateTransitionException(from, to)
        }
    }
}