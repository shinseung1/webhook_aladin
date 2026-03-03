package com.webhook

import com.webhook.application.AccountQueryUseCase
import com.webhook.application.EventQueryUseCase
import com.webhook.application.WebhookProcessingUseCase
import com.webhook.infrastructure.persistence.DatabaseFactory
import com.webhook.infrastructure.persistence.SqliteAccountRepository
import com.webhook.infrastructure.persistence.SqliteEventRepository
import com.webhook.infrastructure.security.HmacSignatureVerifier
import com.webhook.presentation.accountQueryRoutes
import com.webhook.presentation.eventQueryRoutes
import com.webhook.presentation.webhookRoutes
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.response.respond
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.apache.commons.codec.digest.HmacUtils.hmacSha256Hex
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegrationE2EWebhookSpecTest {

    // ✅ 스펙 경로
    private val WEBHOOK_PATH = "/webhooks/account-changes"

    // 테스트용 시크릿 (테스트 실행 시 WEBHOOK_SECRET도 이 값으로 맞춰야 함)
    private val SECRET = System.getenv("WEBHOOK_SECRET")!!

    @BeforeEach
    fun resetDb() {

        DatabaseFactory.transaction { conn ->
            conn.prepareStatement("DELETE FROM events").use { it.executeUpdate() }
            conn.prepareStatement("DELETE FROM accounts").use { it.executeUpdate() }
        }
        // ⚠️ 네 DatabaseFactory가 env로 DB path를 읽는 방식이면,
        // 테스트 실행 커맨드에서 WEBHOOK_DB_PATH를 지정해서 테스트 DB를 분리하는 걸 강력 권장.
        // 예: WEBHOOK_DB_PATH=build/test-webhook-e2e.db
        DatabaseFactory.initSchema()
    }

    // ────────────────────────────────────────────────────────────────────────
    // 1) 서명 성공 + EMAIL 갱신 + 이벤트 DONE (POST → GET accounts + GET event)
    // ────────────────────────────────────────────────────────────────────────
    @Test
    fun `서명 성공 - EMAIL_FORWARDING_CHANGED 처리 후 GET accounts에 갱신된 이메일이 반영되고 이벤트는 DONE이다`() = testApplication {
        // GIVEN: FK 때문에 accounts 선행 생성 필요
        val accountId = "acc-001"
        seedAccount(accountId, name = "tester", email = "old@example.com", status = "ACTIVE")

        val eventId = "evt-email-001"
        val now = Instant.parse("2026-03-01T00:00:00Z")
        val newEmail = "new@example.com"

        // ✅ body는 "정확히" 이 문자열 그대로 사용 (trimIndent OK)
        val body = """
        {
          "eventType": "EMAIL_FORWARDING_CHANGED",
          "accountId": "$accountId",
          "occurredAt": "$now",
          "payload": { "email": "$newEmail" }
        }
    """.trimIndent()

        // ✅ raw bytes로 서명 계산
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val sigHeader = "sha256=${hmacSha256HexBytes(SECRET, bodyBytes)}"

        // WHEN: bytes 그대로 전송 (서명과 완전 일치)
        val postRes = client.post(WEBHOOK_PATH) {
            header("X-Event-Id", eventId)
            header("X-Signature", sigHeader)
            contentType(ContentType.Application.Json)
            setBody(bodyBytes)
        }

        // THEN: webhook 응답 200
        assertEquals(HttpStatusCode.OK, postRes.status, postRes.bodyAsText())

        // THEN: 계정 조회에서 이메일 갱신 확인
        val accRes = client.get("/accounts/$accountId")
        assertEquals(HttpStatusCode.OK, accRes.status, accRes.bodyAsText())
        val accBody = accRes.bodyAsText()
        assertTrue(accBody.contains(newEmail), "Expected updated email in response. body=$accBody")

        // THEN: 이벤트 조회에서 DONE 확인
        val evtRes = client.get("/inbox/events/$eventId")
        assertEquals(HttpStatusCode.OK, evtRes.status, evtRes.bodyAsText())
        val evtBody = evtRes.bodyAsText()
        assertTrue(evtBody.contains("DONE"), "Expected DONE in event response. body=$evtBody")
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2) 서명 실패 → 401 + 이벤트 저장/처리 진입 금지(이벤트 조회 404)
    // ────────────────────────────────────────────────────────────────────────
    @Test
    fun `서명 실패 - 401 반환되고 이벤트는 저장되지 않아 GET inbox events가 404다`() = testApplication {
        val eventId = "evt-badsig-001"
        val accountId = "acc-any" // seed 불필요
        val now = Instant.parse("2026-03-01T00:00:00Z")

        val body = """
    {
      "eventType": "ACCOUNT_DELETED",
      "accountId": "$accountId",
      "occurredAt": "$now",
      "payload": {}
    }
    """.trimIndent()

        val bodyBytes = body.toByteArray(Charsets.UTF_8)

        // 고의로 틀린 서명(64 hex)
        val badSigHeader = "sha256=${"00".repeat(32)}"

        val postRes = client.post(WEBHOOK_PATH) {
            header("X-Event-Id", eventId)
            header("X-Signature", badSigHeader)
            contentType(ContentType.Application.Json)
            setBody(bodyBytes)
        }
        assertEquals(HttpStatusCode.Unauthorized, postRes.status, postRes.bodyAsText())

        val evtRes = client.get("/inbox/events/$eventId")
        assertEquals(HttpStatusCode.NotFound, evtRes.status, evtRes.bodyAsText())
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3) ACCOUNT_DELETED → status=DELETED + 이벤트 DONE
    // ────────────────────────────────────────────────────────────────────────
    @Test
    fun `ACCOUNT_DELETED 처리 후 GET accounts에 status=DELETED가 반영되고 이벤트는 DONE이다`() = testApplication {
        val accountId = "acc-003"
        seedAccount(accountId, name = "tester3", email = "a3@example.com", status = "ACTIVE")

        val eventId = "evt-del-001"
        val now = Instant.parse("2026-03-01T00:00:00Z")

        val body = """
            {
              "eventType": "ACCOUNT_DELETED",
              "accountId": "$accountId",
              "occurredAt": "$now",
              "payload": {}
            }
            """.trimIndent()

        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val sigHeader = "sha256=${hmacSha256HexBytes(SECRET, bodyBytes)}"

        val postRes = client.post(WEBHOOK_PATH) {
            header("X-Event-Id", eventId)
            header("X-Signature", sigHeader)              // ✅ sigHeader 사용
            contentType(ContentType.Application.Json)
            setBody(bodyBytes)                            // ✅ bytes로 전송 (raw body 일치)
        }

        assertEquals(HttpStatusCode.OK, postRes.status, postRes.bodyAsText())

        val accRes = client.get("/accounts/$accountId")
        assertEquals(HttpStatusCode.OK, accRes.status, accRes.bodyAsText())
        val accBody = accRes.bodyAsText()

        // ✅ 요구사항: DELETED
        assertTrue(accBody.contains("DELETED"), "Expected status=DELETED in response. body=$accBody")

        val evtRes = client.get("/inbox/events/$eventId")
        assertEquals(HttpStatusCode.OK, evtRes.status, evtRes.bodyAsText())
        val evtBody = evtRes.bodyAsText()
        assertTrue(evtBody.contains("DONE"), "Expected DONE in event response. body=$evtBody")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Ktor testApplication wiring (실제 repo/usecase/verifier 연결)
    // ────────────────────────────────────────────────────────────────────────
    private fun testApplication(block: suspend ApplicationTestBuilder.() -> Unit) = io.ktor.server.testing.testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(StatusPages) {
                exception<Throwable> { call, _ ->
                    call.respond(HttpStatusCode.InternalServerError, "Internal error")
                }
            }

            // ⚠️ verifier가 env 기반 secret을 읽는다면, 테스트 실행 시 WEBHOOK_SECRET=test-secret 필요
            // (AppConfig.load()를 쓰는 구조면 application에서 config를 직접 구성해도 됨)
            val verifier = HmacSignatureVerifier()

            val eventRepo = SqliteEventRepository()
            val accountRepo = SqliteAccountRepository()

            val processUseCase = WebhookProcessingUseCase(eventRepo, accountRepo)
            val eventQueryUseCase = EventQueryUseCase(eventRepo)
            val accountQueryUseCase = AccountQueryUseCase(accountRepo)

            routing {
                webhookRoutes(processUseCase, verifier)
                eventQueryRoutes(eventQueryUseCase)
                accountQueryRoutes(accountQueryUseCase)
            }
        }
        block()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────
    private fun hmacHex(body: ByteArray, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val out = mac.doFinal(body)
        return out.joinToString("") { "%02x".format(it) }
    }

    /**
     * FK 때문에 accounts 선행 insert 필요.
     * accounts 컬럼명이 다르면 여기만 네 스키마에 맞춰 수정.
     */
    private fun seedAccount(accountId: String, name: String, email: String, status: String) {
        DatabaseFactory.transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO accounts(account_id, name, email, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                val now = Instant.parse("2026-03-01T00:00:00Z").toString()
                ps.setString(1, accountId)
                ps.setString(2, name)
                ps.setString(3, email)
                ps.setString(4, status)
                ps.setString(5, now)
                ps.setString(6, now)
                ps.executeUpdate()
            }
        }
    }

    private fun hmacSha256HexBytes(secret: String, bodyBytes: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(bodyBytes).joinToString("") { "%02x".format(it) }
    }
}