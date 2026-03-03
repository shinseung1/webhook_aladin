package com.webhook.presentation.dto

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val error: String)