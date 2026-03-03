package com.webhook

import com.webhook.domain.model.AccountStatus
import com.webhook.domain.service.WebhookBusinessException
import com.webhook.infrastructure.persistence.DatabaseFactory
import com.webhook.infrastructure.persistence.SqliteAccountRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class SqliteAccountRepositoryTest {

    private lateinit var repo: SqliteAccountRepository

    private val accountId = "acc-001"
    private val now = Instant.parse("2026-03-01T00:00:00Z")

    @BeforeEach
    fun setUp() {
        // ✅ 테스트 DB는 in-memory로 고정 (파일 DB 오염/잔여데이터 이슈 방지)
        DatabaseFactory.connect("jdbc:sqlite::memory:")
        DatabaseFactory.initSchema()
        repo = SqliteAccountRepository()
    }

    private fun seedAccount(
        email: String = "seungho_old@example.com",
        status: String = AccountStatus.ACTIVE.name
    ) {
        // upsert가 받는 JSON 포맷(현재 구현에 맞춤)
        val raw = """{"name":"seunghoShin","email":"$email","status":"$status"}"""
        repo.upsert(accountId, raw, now)
    }

    private fun readAccountRow(accountId: String): Triple<String, String, String> {
        // (name, email, status)
        return DatabaseFactory.transaction { conn ->
            conn.prepareStatement(
                "SELECT name, email, status FROM accounts WHERE account_id = ?"
            ).use { ps ->
                ps.setString(1, accountId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) throw IllegalStateException("Account not found in DB: $accountId")
                    Triple(rs.getString("name"), rs.getString("email"), rs.getString("status"))
                }
            }
        }
    }

    @Test
    fun `updateEmailForwarding는 email을 갱신한다`() {
        seedAccount(email = "seungho_old@example.com", status = AccountStatus.ACTIVE.name)

        val updatePayload = """{"email":"seungho_new@example.com"}"""
        repo.updateEmailForwarding(accountId, updatePayload, now)

        val (_, email, status) = readAccountRow(accountId)
        assertEquals("seungho_new@example.com", email)
        assertEquals(AccountStatus.ACTIVE.name, status) // 상태는 유지
    }


    @Test
    fun `markAppleDeleted는 status를 APPLE_DELETED로 변경한다`() {
        seedAccount(email = "seungho_old@example.com", status = AccountStatus.ACTIVE.name)

        repo.markAppleDeleted(accountId, now)

        val (_, _, status) = readAccountRow(accountId)
        assertEquals(AccountStatus.APPLE_DELETED.name, status)
    }

    @Test
    fun `deleteOrClose는 status를 DELETED로 변경한다`() {
        seedAccount(email = "seungho_old@example.com", status = AccountStatus.ACTIVE.name)

        repo.deleteOrClose(accountId, now)

        val (_, _, status) = readAccountRow(accountId)
        assertEquals(AccountStatus.DELETED.name, status)
    }
}