package com.webhook.domain.service

import com.webhook.domain.model.EventStatus

class InvalidStateTransitionException(
    from: EventStatus,
    to: EventStatus
) : Exception("Invalid state transition: $from -> $to")