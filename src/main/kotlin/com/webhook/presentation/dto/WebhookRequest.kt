package com.webhook.presentation.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class WebhookRequest(
    val eventType: String,
    val accountId: String,
    val payload: JsonElement,
    val occurredAt: String
)