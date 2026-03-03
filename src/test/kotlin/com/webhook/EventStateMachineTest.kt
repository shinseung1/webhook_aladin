package com.webhook

import com.webhook.domain.model.EventStatus
import com.webhook.domain.service.EventStateMachine
import com.webhook.domain.service.InvalidStateTransitionException
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class EventStateMachineTest {

    @Test
    fun `RECEIVED to PROCESSING is allowed`() {
        Assertions.assertTrue(EventStateMachine.canTransition(EventStatus.RECEIVED, EventStatus.PROCESSING))
    }

    @Test
    fun `PROCESSING to DONE is allowed`() {
        Assertions.assertTrue(EventStateMachine.canTransition(EventStatus.PROCESSING, EventStatus.DONE))
    }

    @Test
    fun `PROCESSING to FAILED is allowed`() {
        Assertions.assertTrue(EventStateMachine.canTransition(EventStatus.PROCESSING, EventStatus.FAILED))
    }

    @Test
    fun `DONE to PROCESSING is not allowed`() {
        Assertions.assertFalse(EventStateMachine.canTransition(EventStatus.DONE, EventStatus.PROCESSING))
    }

    @Test
    fun `FAILED to PROCESSING is not allowed`() {
        Assertions.assertFalse(EventStateMachine.canTransition(EventStatus.FAILED, EventStatus.PROCESSING))
    }

    @Test
    fun `requireTransition does not throw for allowed transitions`() {
        EventStateMachine.requireTransition(EventStatus.RECEIVED, EventStatus.PROCESSING)
        EventStateMachine.requireTransition(EventStatus.PROCESSING, EventStatus.DONE)
        EventStateMachine.requireTransition(EventStatus.PROCESSING, EventStatus.FAILED)
    }

    @Test
    fun `requireTransition throws for DONE to PROCESSING`() {
        Assertions.assertThrows(InvalidStateTransitionException::class.java) {
            EventStateMachine.requireTransition(EventStatus.DONE, EventStatus.PROCESSING)
        }
    }

    @Test
    fun `requireTransition throws for FAILED to PROCESSING`() {
        Assertions.assertThrows(InvalidStateTransitionException::class.java) {
            EventStateMachine.requireTransition(EventStatus.FAILED, EventStatus.PROCESSING)
        }
    }
}