package com.webhook.presentation

import com.webhook.application.ProcessWebhookCommand
import com.webhook.application.ProcessWebhookResult
import com.webhook.application.WebhookProcessingUseCase
import com.webhook.domain.model.EventStatus
import com.webhook.infrastructure.security.SignatureVerifier
import com.webhook.presentation.dto.ErrorResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class WebhookControllerTest {

    private val eventId   = "evt-001"
    private val sig       = "sha256=deadbeef"
    private val validBody =
        """{"eventType":"ACCOUNT_CREATED","accountId":"acc-001","payload":{},"occurredAt":"2024-01-01T00:00:00Z"}"""

    private fun doneResult(dup: Boolean = false) =
        ProcessWebhookResult(eventId = eventId, status = EventStatus.DONE, isDuplicate = dup)

    private fun webhookTestApp(
        verifier: SignatureVerifier,
        useCase: WebhookProcessingUseCase,
        block: suspend ApplicationTestBuilder.() -> Unit
    ) = testApplication {
        application {
            // ✅ receiver 명시
            this.install(ContentNegotiation) { json() }

            this.install(StatusPages) {
                exception<Throwable> { call, _ ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Internal error")
                    )
                }
            }

            // ✅ 이것도 receiver 명시해두면 안전
            this.routing { webhookRoutes(useCase, verifier) }
        }

        block()
    }

    @Test
    fun `HMAC 불일치 - verifier false이면 401 반환하고 useCase 미호출`() {
        val verifier = mockk<SignatureVerifier>()
        val useCase  = mockk<WebhookProcessingUseCase>()
        every { verifier.verify(any(), any()) } returns false

        webhookTestApp(verifier, useCase) {
            val resp = client.post("/webhooks/account-changes") {
                header("X-Event-Id", eventId)
                header("X-Signature", sig)
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            verify(exactly = 0) { useCase.process(any()) }
        }
    }

    @Test
    fun `X-Signature 헤더 없으면 401 반환하고 useCase 미호출`() {
        val verifier = mockk<SignatureVerifier>()
        val useCase  = mockk<WebhookProcessingUseCase>()

        webhookTestApp(verifier, useCase) {
            val resp = client.post("/webhooks/account-changes") {
                header("X-Event-Id", eventId)
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            //수정 26.03.01
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            verify(exactly = 0) { useCase.process(any()) }
        }
    }

    @Test
    fun `잘못된 JSON body이면 400 반환하고 useCase 미호출`() {
        val verifier = mockk<SignatureVerifier>()
        val useCase  = mockk<WebhookProcessingUseCase>()
        every { verifier.verify(any(), any()) } returns true

        webhookTestApp(verifier, useCase) {
            val resp = client.post("/webhooks/account-changes") {
                header("X-Event-Id", eventId)
                header("X-Signature", sig)
                contentType(ContentType.Application.Json)
                setBody("{")
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            verify(exactly = 0) { useCase.process(any()) }
        }
    }

    @Test
    fun `accountId 공백이면 400 반환하고 useCase 미호출`() {
        val verifier  = mockk<SignatureVerifier>()
        val useCase   = mockk<WebhookProcessingUseCase>()
        val blankBody =
            """{"eventType":"ACCOUNT_CREATED","accountId":"   ","payload":{},"occurredAt":"2026-03-01T00:00:00Z"}"""
        every { verifier.verify(any(), any()) } returns true

        webhookTestApp(verifier, useCase) {
            val resp = client.post("/webhooks/account-changes") {
                header("X-Event-Id", eventId)
                header("X-Signature", sig)
                contentType(ContentType.Application.Json)
                setBody(blankBody)
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            verify(exactly = 0) { useCase.process(any()) }
        }
    }

    @Test
    fun `정상 처리 DONE이면 200 반환하고 useCase 1회 호출`() {
        val verifier = mockk<SignatureVerifier>()
        val useCase  = mockk<WebhookProcessingUseCase>()
        every { verifier.verify(any(), any()) } returns true
        every { useCase.process(any()) } returns doneResult()

        webhookTestApp(verifier, useCase) {
            val resp = client.post("/webhooks/account-changes") {
                header("X-Event-Id", eventId)
                header("X-Signature", sig)
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            verify(exactly = 1) { useCase.process(any()) }
        }
    }

    @Test
    fun `처리 결과 FAILED여도 200 반환`() {
        val verifier = mockk<SignatureVerifier>()
        val useCase  = mockk<WebhookProcessingUseCase>()
        every { verifier.verify(any(), any()) } returns true
        every { useCase.process(any()) } returns ProcessWebhookResult(
            eventId = eventId, status = EventStatus.FAILED,
            isDuplicate = false, errorMessage = "downstream error"
        )

        webhookTestApp(verifier, useCase) {
            val resp = client.post("/webhooks/account-changes") {
                header("X-Event-Id", eventId)
                header("X-Signature", sig)
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            verify(exactly = 1) { useCase.process(any()) }
        }
    }

    @Test
    fun `중복 이벤트 isDuplicate=true여도 200 반환`() {
        val verifier = mockk<SignatureVerifier>()
        val useCase  = mockk<WebhookProcessingUseCase>()
        every { verifier.verify(any(), any()) } returns true
        every { useCase.process(any()) } returns doneResult(dup = true)

        webhookTestApp(verifier, useCase) {
            val resp = client.post("/webhooks/account-changes") {
                header("X-Event-Id", eventId)
                header("X-Signature", sig)
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            assertEquals(HttpStatusCode.OK, resp.status)
        }
    }

    @Test
    fun `useCase RuntimeException이면 500 반환`() {
        val verifier = mockk<SignatureVerifier>()
        val useCase  = mockk<WebhookProcessingUseCase>()
        every { verifier.verify(any(), any()) } returns true
        every { useCase.process(any()) } throws RuntimeException("DB connection lost")

        webhookTestApp(verifier, useCase) {
            val resp = client.post("/webhooks/account-changes") {
                header("X-Event-Id", eventId)
                header("X-Signature", sig)
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            assertEquals(HttpStatusCode.InternalServerError, resp.status)
        }
    }
}
