package com.webhook.presentation.dto

import kotlinx.serialization.Serializable

@Serializable
data class WebhookResponse(
    val eventId: String,
    val status: String,
    val isDuplicate: Boolean = false,
    val error: String? = null
)
