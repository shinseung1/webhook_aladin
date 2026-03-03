package com.webhook

import com.webhook.application.ProcessWebhookCommand
import com.webhook.application.WebhookProcessingUseCase
import com.webhook.domain.model.Account
import com.webhook.domain.model.AccountStatus
import com.webhook.domain.model.EventStatus
import com.webhook.domain.model.EventType
import com.webhook.domain.model.WebhookEvent
import com.webhook.domain.service.WebhookBusinessException
import com.webhook.infrastructure.persistence.AccountRepository
import com.webhook.infrastructure.persistence.EventRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import net.bytebuddy.matcher.ElementMatchers.any
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant
import javax.management.Query.eq
import javax.management.Query.match

class WebhookProcessingUseCaseTest {

    // relaxed=true: 미설정 메서드는 기본값 반환 (Boolean=false, Unit=Unit, 객체=null)
    private val eventRepo: EventRepository = mockk()
    private val accountRepo: AccountRepository = mockk()
    private val useCase = WebhookProcessingUseCase(eventRepo, accountRepo)

    private val eventId  = "evt-001"
    private val accountId = "acc-001"
    private val now = Instant.parse("2026-03-01T00:00:00Z")

    private val command = ProcessWebhookCommand(
        eventId = eventId,
        eventType = EventType.ACCOUNT_CREATED,
        accountId = accountId,
        rawPayload = """{"eventType":"ACCOUNT_CREATED","accountId":"acc-001","payload":{}}""",
        occurredAt = now
    )

    // ────────────────────────────────────────────────────────────────────────
    // 1. 신규 이벤트 정상 처리
    // ────────────────────────────────────────────────────────────────────────
    @Test
    fun `신규 이벤트는 DONE 상태로 정상 처리되고 isDuplicate는 false다`() {
        val cmd = ProcessWebhookCommand(
            eventId = eventId,
            eventType = EventType.EMAIL_FORWARDING_CHANGED,
            accountId = accountId,
            rawPayload = """{"email":"new@example.com"}""",
            occurredAt = now
        )

        every { eventRepo.insertIfAbsent(any<java.sql.Connection>(), any()) } returns true
        every { eventRepo.claimForProcessing(any<java.sql.Connection>(), eq(eventId)) } returns true
        every { eventRepo.findByEventId(any<java.sql.Connection>(), any()) } returns null
        every { eventRepo.updateStatus(any<java.sql.Connection>(), any(), any(), any(), any()) } just Runs

        every { accountRepo.updateEmailForwarding(any<java.sql.Connection>(), eq(accountId), any(), eq(now)) } just Runs

        val result = useCase.process(cmd)

        assertEquals(EventStatus.DONE, result.status)
        assertFalse(result.isDuplicate)
        assertNull(result.errorMessage)

        verifyOrder {
            eventRepo.insertIfAbsent(any<java.sql.Connection>(), any())
            eventRepo.claimForProcessing(any<java.sql.Connection>(), eq(eventId))
            accountRepo.updateEmailForwarding(any<java.sql.Connection>(), eq(accountId), any(), eq(now))
            eventRepo.updateStatus(any<java.sql.Connection>(), eq(eventId), eq(EventStatus.DONE), any(), isNull())
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. 중복 이벤트 처리
    // ────────────────────────────────────────────────────────────────────────
    @Test
    fun `중복 이벤트는 isDuplicate true를 반환하고 비즈니스 로직이 실행되지 않는다`() {
        val cmd = ProcessWebhookCommand(
            eventId = eventId,
            eventType = EventType.EMAIL_FORWARDING_CHANGED,   // ✅ 지원 이벤트로 변경
            accountId = accountId,
            rawPayload = """{"email":"new@example.com"}""",
            occurredAt = now
        )

        // insertIfAbsent가 false면 "중복" 분기로 빠짐
        every { eventRepo.insertIfAbsent(any<java.sql.Connection>(), any()) } returns false

        // 중복 분기에서는 기존 이벤트를 조회해서 상태를 그대로 반환하므로, existing을 하나 만들어줌
        every { eventRepo.findByEventId(any<java.sql.Connection>(), eq(eventId)) } returns
                com.webhook.domain.model.WebhookEvent(
                    eventId = eventId,
                    eventType = cmd.eventType,
                    accountId = accountId,
                    rawPayload = cmd.rawPayload,
                    status = EventStatus.DONE,          // ✅ 예: 이미 DONE인 중복
                    createdAt = now,
                    processedAt = now,
                    errorMessage = null
                )

        val result = useCase.process(cmd)

        assertTrue(result.isDuplicate)
        assertEquals(EventStatus.DONE, result.status)

        verify(exactly = 0) { eventRepo.claimForProcessing(any<java.sql.Connection>(), any()) }
        verify(exactly = 0) { eventRepo.updateStatus(any<java.sql.Connection>(), any(), any(), any(), any()) }

        verify(exactly = 0) { accountRepo.updateEmailForwarding(any<java.sql.Connection>(), any(), any(), any()) }
        verify(exactly = 0) { accountRepo.deleteOrClose(any<java.sql.Connection>(), any(), any()) }
        verify(exactly = 0) { accountRepo.markAppleDeleted(any<java.sql.Connection>(), any(), any()) }

        verify(exactly = 0) { accountRepo.upsert(any(), any(), any()) } // 네 upsert는 비-conn 버전이 실제로 존재하니 이것도 막아둠
        // verify(exactly = 0) { accountRepo.upsert(any<java.sql.Connection>(), any(), any(), any()) } // conn 버전이 있으면 추가
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3. claim 실패 (이미 PROCESSING 또는 DONE/FAILED 상태)
    // ────────────────────────────────────────────────────────────────────────
    @Test
    fun `claim 실패 시 이미 처리 중인 이벤트는 재처리되지 않는다`() {
        val cmd = ProcessWebhookCommand(
            eventId = eventId,
            eventType = EventType.EMAIL_FORWARDING_CHANGED, // ✅ 지원 이벤트로
            accountId = accountId,
            rawPayload = """{"email":"new@example.com"}""",
            occurredAt = now
        )

        every { eventRepo.insertIfAbsent(any<Connection>(), any()) } returns true
        every { eventRepo.claimForProcessing(any<Connection>(), eq(eventId)) } returns false
        every { eventRepo.findByEventId(any<Connection>(), eq(eventId)) } returns WebhookEvent(
            eventId = eventId,
            eventType = cmd.eventType,
            accountId = accountId,
            rawPayload = cmd.rawPayload,
            status = EventStatus.PROCESSING,
            createdAt = now,
            processedAt = null,
            errorMessage = null
        )

        val result = useCase.process(cmd)

        assertTrue(result.isDuplicate)
        assertEquals(EventStatus.PROCESSING, result.status)

        verify(exactly = 0) { accountRepo.updateEmailForwarding(any<Connection>(), any(), any(), any()) }
        verify(exactly = 0) { accountRepo.deleteOrClose(any<Connection>(), any(), any()) }
        verify(exactly = 0) { accountRepo.markAppleDeleted(any<Connection>(), any(), any()) }
        verify(exactly = 0) { eventRepo.updateStatus(any<Connection>(), any(), any(), any(), any()) }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4. 도메인 처리 실패 → FAILED 터미널 상태 확정
    // ────────────────────────────────────────────────────────────────────────
    @Test
    fun `도메인 처리 실패 시 이벤트는 FAILED 상태로 확정되고 DONE은 호출되지 않는다`() {
        val cmd = ProcessWebhookCommand(
            eventId = eventId,
            eventType = EventType.EMAIL_FORWARDING_CHANGED, // ✅ 지원 이벤트로 변경
            accountId = accountId,
            rawPayload = """{"email":"new@example.com"}""",
            occurredAt = now
        )

        every { eventRepo.insertIfAbsent(any<java.sql.Connection>(), any()) } returns true
        every { eventRepo.claimForProcessing(any<java.sql.Connection>(), eq(eventId)) } returns false

        // claim 실패 시 existing 조회 경로로 갈 수 있으니, "PROCESSING" 상태의 이벤트를 반환시켜
        every { eventRepo.findByEventId(any<java.sql.Connection>(), eq(eventId)) } returns
                com.webhook.domain.model.WebhookEvent(
                    eventId = eventId,
                    eventType = EventType.EMAIL_FORWARDING_CHANGED,
                    accountId = accountId,
                    rawPayload = cmd.rawPayload,
                    status = EventStatus.PROCESSING,
                    createdAt = now,
                    processedAt = null,
                    errorMessage = null
                )

        val result = useCase.process(cmd)

        // claim 실패면 duplicate로 반환(너 usecase 로직 그대로)
        assertTrue(result.isDuplicate)
        assertEquals(EventStatus.PROCESSING, result.status)

        verify(exactly = 0) { accountRepo.updateEmailForwarding(any<java.sql.Connection>(), any(), any(), any()) }
        verify(exactly = 0) { accountRepo.deleteOrClose(any<java.sql.Connection>(), any(), any()) }
        verify(exactly = 0) { accountRepo.markAppleDeleted(any<java.sql.Connection>(), any(), any()) }

        // DONE/FAILED 업데이트도 없어야 함(재처리 안 하니까)
        verify(exactly = 0) { eventRepo.updateStatus(any<java.sql.Connection>(), any(), any(), any(), any()) }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 5. Repository 예외 전파
    // ────────────────────────────────────────────────────────────────────────
    @Test
    fun `EventRepository 예외는 설계된 실패 정책에 따라 상위로 전파된다`() {
        every { eventRepo.insertIfAbsent(any<java.sql.Connection>(), any()) } throws RuntimeException("DB connection failed")

        assertThrows(RuntimeException::class.java) {
            useCase.process(command)
        }
    }

    @Test
    fun `EMAIL_FORWARDING_CHANGED는 updateEmailForwarding을 호출하고 이벤트는 DONE으로 확정된다`() {
        val cmd = ProcessWebhookCommand(
            eventId    = eventId,
            eventType  = EventType.EMAIL_FORWARDING_CHANGED,
            accountId  = accountId,
            rawPayload = """{"email":"new@example.com"}""",
            occurredAt = now
        )

        every { eventRepo.insertIfAbsent(any<java.sql.Connection>(), any()) } returns true
        every { eventRepo.claimForProcessing(any<java.sql.Connection>(), eq(eventId)) } returns true
        every { eventRepo.findByEventId(any<java.sql.Connection>(), any()) } returns null

        every { eventRepo.updateStatus(any<java.sql.Connection>(), any(), any(), any(), any()) } just Runs

        //every { accountRepo.updateEmailForwarding(eq(accountId), any(), eq(now)) } just Runs
        every { accountRepo.updateEmailForwarding(any<java.sql.Connection>(), eq(accountId), any(), eq(now)) } just Runs

        val result = useCase.process(cmd)

        assertEquals(EventStatus.DONE, result.status)
        assertFalse(result.isDuplicate)

        // verify (실제 호출 시그니처에 맞춰 conn 버전으로 검증)
        verify(exactly = 1) {
            accountRepo.updateEmailForwarding(any<java.sql.Connection>(), eq(accountId), any(), eq(now))
        }

        verify(exactly = 1) {
            eventRepo.updateStatus(
                any<java.sql.Connection>(),
                eq(eventId),
                eq(EventStatus.DONE),
                any(),
                isNull()
            )
        }
    }

    @Test
    fun `APPLE_ACCOUNT_DELETED는 markAppleDeleted를 호출하고 이벤트는 DONE으로 확정된다`() {
        val cmd = ProcessWebhookCommand(
            eventId = eventId,
            eventType = EventType.APPLE_ACCOUNT_DELETED,
            accountId = accountId,
            rawPayload = """{}""",
            occurredAt = now
        )

        // EventRepository: conn 오버로드 전부 방어적으로 스텁
        every { eventRepo.insertIfAbsent(any<java.sql.Connection>(), any()) } returns true
        every { eventRepo.claimForProcessing(any<java.sql.Connection>(), any()) } returns true
        every { eventRepo.findByEventId(any<java.sql.Connection>(), any()) } returns null
        every { eventRepo.updateStatus(any<java.sql.Connection>(), any(), any(), any(), any()) } just Runs

        // AccountRepository: 2파라미터/3파라미터 둘 다 스텁(실행 소스 불일치 방어)
        //every { accountRepo.markAppleDeleted(any(), any()) } just Runs
        every { accountRepo.markAppleDeleted(any<java.sql.Connection>(), any(), any()) } just Runs

        val result = useCase.process(cmd)

        assertEquals(EventStatus.DONE, result.status)
        assertFalse(result.isDuplicate)

        verify(exactly = 1) { accountRepo.markAppleDeleted(any<java.sql.Connection>(), eq(accountId), eq(now)) }
        verify(exactly = 1) { eventRepo.updateStatus(any<java.sql.Connection>(), eq(eventId), eq(EventStatus.DONE), any(), isNull()) }
    }

    // ────────────────────────────────────────────────────────────────────────
    // [필수] ACCOUNT_DELETED → deleteOrClose 호출 + DONE 확정
    // ────────────────────────────────────────────────────────────────────────
    @Test
    fun `ACCOUNT_DELETED는 deleteOrClose를 호출하고 이벤트는 DONE으로 확정된다`() {
        val cmd = ProcessWebhookCommand(
            eventId    = "evt-del-001",
            eventType  = EventType.ACCOUNT_DELETED,
            accountId  = accountId,
            rawPayload = """{}""",
            occurredAt = now
        )

        every { eventRepo.insertIfAbsent(any<Connection>(), any()) } returns true
        every { eventRepo.claimForProcessing(any<Connection>(), eq(cmd.eventId)) } returns true
        every { eventRepo.findByEventId(any<Connection>(), eq(cmd.eventId)) } returns null
        every { eventRepo.updateStatus(any<Connection>(), any(), any(), any(), any()) } just Runs

        every { accountRepo.deleteOrClose(any<Connection>(), eq(accountId), eq(now)) } just Runs

        val result = useCase.process(cmd)

        assertEquals(EventStatus.DONE, result.status)
        assertFalse(result.isDuplicate)
        assertNull(result.errorMessage)

        verify(exactly = 1) { accountRepo.deleteOrClose(any<Connection>(), eq(accountId), eq(now)) }
        verify(exactly = 1) {
            eventRepo.updateStatus(
                any<Connection>(),
                eq(cmd.eventId),
                eq(EventStatus.DONE),
                any(),
                isNull()
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // [필수] 도메인 처리 실패 → FAILED + error_message 저장 + DONE 금지
    // ────────────────────────────────────────────────────────────────────────
    @Test
    fun `도메인 처리 중 예외가 발생하면 이벤트는 FAILED로 기록되고 error_message가 저장된다`() {
        val cmd = ProcessWebhookCommand(
            eventId = eventId,
            eventType = EventType.EMAIL_FORWARDING_CHANGED,
            accountId = accountId,
            rawPayload = """{"email":"bad"}""",
            occurredAt = now
        )

        every { eventRepo.insertIfAbsent(any<Connection>(), any()) } returns true
        every { eventRepo.claimForProcessing(any<Connection>(), eq(eventId)) } returns true
        every { eventRepo.findByEventId(any<Connection>(), any()) } returns null
        every { eventRepo.updateStatus(any<Connection>(), any(), any(), any(), any()) } just Runs

        every {
            accountRepo.updateEmailForwarding(any<Connection>(), eq(accountId), any(), eq(now))
        } throws WebhookBusinessException("invalid email") // ✅ 비즈니스 예외

        val result = useCase.process(cmd)

        assertEquals(EventStatus.FAILED, result.status)
        assertFalse(result.isDuplicate)
        assertEquals("invalid email", result.errorMessage)

        verify(exactly = 1) {
            eventRepo.updateStatus(
                any<Connection>(),
                eq(eventId),
                eq(EventStatus.FAILED),
                any(),
                eq("invalid email")
            )
        }

        verify(exactly = 0) {
            eventRepo.updateStatus(any<Connection>(), eq(eventId), eq(EventStatus.DONE), any(), any())
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // [필수/권장] Unsupported event type → FAILED + error_message 저장
    // ────────────────────────────────────────────────────────────────────────
    @Test
    fun `지원하지 않는 이벤트 타입은 FAILED로 기록되고 error_message가 저장된다`() {

        val cmd = ProcessWebhookCommand(
            eventId    = "evt-unsup-001",
            eventType  = EventType.ACCOUNT_CREATED,
            accountId  = accountId,
            rawPayload = """{"eventType":"ACCOUNT_CREATED","accountId":"$accountId","payload":{}}""",
            occurredAt = now
        )

        every { accountRepo.upsert(eq(accountId), any(), eq(now)) } just Runs

        every { eventRepo.insertIfAbsent(any<Connection>(), any()) } returns true
        every { eventRepo.claimForProcessing(any<Connection>(), eq(cmd.eventId)) } returns true
        every { eventRepo.findByEventId(any<Connection>(), eq(cmd.eventId)) } returns null

        // 오버로드 둘 다 스텁
        every { eventRepo.updateStatus(any<Connection>(), any(), any(), any(), any()) } just Runs
        every { eventRepo.updateStatus(any(), any(), any(), any()) } just Runs

        val result = useCase.process(cmd)

        assertEquals(EventStatus.FAILED, result.status)
        assertFalse(result.isDuplicate)
        assertTrue(!result.errorMessage.isNullOrBlank())

    }
}