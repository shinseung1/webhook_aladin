package com.webhook.presentation

import com.webhook.application.ProcessWebhookCommand
import com.webhook.application.WebhookProcessingUseCase
import com.webhook.domain.model.EventType
import com.webhook.infrastructure.security.SignatureVerifier
import com.webhook.presentation.dto.ErrorResponse
import com.webhook.presentation.dto.WebhookRequest
import com.webhook.presentation.dto.WebhookResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.core.readBytes
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException

private val json = Json { ignoreUnknownKeys = true }

fun Route.webhookRoutes(
    useCase: WebhookProcessingUseCase,
    verifier: SignatureVerifier
) {

    post("/webhooks/account-changes") {

        val rawBytes = call.receiveChannel().readRemaining().readBytes()
        val eventId = call.request.headers["X-Event-Id"]

        if (eventId.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Missing X-Event-Id header")
            )
            return@post
        }

        val signatureHeader = call.request.headers["X-Signature"]
        if (signatureHeader.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse("Missing signature")
            )
            return@post
        }

        if (!verifier.verify(rawBytes, signatureHeader)) {
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse("Invalid signature")
            )
            return@post
        }

        val rawString = rawBytes.decodeToString()

        val dto: WebhookRequest = try {
            json.decodeFromString(rawString)
        } catch (_: SerializationException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Invalid JSON body")
            )
            return@post
        }

        if (dto.accountId.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("accountId must not be blank")
            )
            return@post
        }

        val occurredAt = try {
            Instant.parse(dto.occurredAt)
        } catch (_: DateTimeParseException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Invalid occurredAt format")
            )
            return@post
        }

        val eventType = try {
            EventType.valueOf(dto.eventType)
        } catch (_: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Invalid eventType")
            )
            return@post
        }

        try {
            val cmd = ProcessWebhookCommand(
                eventId = eventId,
                eventType = eventType,
                accountId = dto.accountId,
                rawPayload = dto.payload.toString(), // 기존 정책 유지
                occurredAt = occurredAt
            )

            val result = useCase.process(cmd)

            call.respond(
                HttpStatusCode.OK,
                WebhookResponse(
                    eventId = result.eventId,
                    status = result.status.name,
                    isDuplicate = result.isDuplicate,
                    error = result.errorMessage
                )
            )
        } catch (e: Exception) {
            call.application.environment.log.error(
                "POST /webhooks/account-changes failed. eventId=$eventId",
                e
            )
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Internal error")
            )
        }
    }
}