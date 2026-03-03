package com.webhook.infrastructure.persistence

import com.webhook.domain.model.EventStatus
import com.webhook.domain.model.EventType
import com.webhook.domain.model.WebhookEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class SqliteEventRepositoryTest {

    private lateinit var repo: SqliteEventRepository

    @BeforeEach
    fun setUp() {
        DatabaseFactory.connect()   // ← init() 아님, connect()
        DatabaseFactory.initSchema()

        //남아있는 데이터 제거 ( 26.03.02 ) 테스트 케이스 오류
        DatabaseFactory.transaction { conn ->
            conn.prepareStatement("DELETE FROM events").use { it.executeUpdate() }
            conn.prepareStatement("DELETE FROM accounts").use { it.executeUpdate() }
        }
        repo = SqliteEventRepository()
    }

    private fun fixture(
        eventId: String      = "evt-001",
        eventType: EventType = EventType.ACCOUNT_CREATED,
        accountId: String    = "acc-001"
    ) = WebhookEvent(
        eventId      = eventId,
        eventType    = eventType,
        accountId    = accountId,
        rawPayload   = """{"eventType":"ACCOUNT_CREATED","accountId":"$accountId"}""",
        status       = EventStatus.RECEIVED,
        createdAt    = Instant.parse("2024-01-01T00:00:00Z"),
        processedAt  = null,
        errorMessage = null
    )

    // ── 1. insertIfAbsent - 신규 ──────────────────────────────────────────────

    @Test
    fun `insertIfAbsent - 신규 이벤트는 true를 반환하고 RECEIVED 상태로 저장된다`() {
        val event = fixture()
        insertAccount(event.accountId)

        val result = repo.insertIfAbsent(event)

        assertTrue(result)
        val saved = repo.findByEventId(event.eventId)
        assertNotNull(saved)
        assertEquals(EventStatus.RECEIVED, saved!!.status)
    }

    // ── 2. insertIfAbsent - 중복 ──────────────────────────────────────────────

    @Test
    fun `insertIfAbsent - 중복 이벤트는 false를 반환하고 기존 데이터를 덮어쓰지 않는다`() {
        val event = fixture()
        insertAccount(event.accountId)

        repo.insertIfAbsent(event)

        val result = repo.insertIfAbsent(event)

        assertFalse(result)
        val saved = repo.findByEventId(event.eventId)!!
        assertEquals(EventStatus.RECEIVED, saved.status)       // 상태 변경 없음
        assertEquals(event.rawPayload, saved.rawPayload)       // 페이로드 덮어쓰기 없음
    }

    // ── 3. claimForProcessing 성공 ────────────────────────────────────────────

    @Test
    fun `claimForProcessing - RECEIVED 이벤트는 true를 반환하고 PROCESSING으로 전이된다`() {
        val event = fixture()
//        val inserted = repo.insertIfAbsent(event)
//        assertTrue(inserted)
//
//        assertEquals(EventStatus.RECEIVED, repo.findByEventId(event.eventId)!!.status)
        insertAccount(event.accountId)

        repo.insertIfAbsent(event)

        val claimed = repo.claimForProcessing(event.eventId)

        assertTrue(claimed)
        assertEquals(EventStatus.PROCESSING, repo.findByEventId(event.eventId)!!.status)
    }

    // ── 4. claimForProcessing 실패 ────────────────────────────────────────────

    @Test
    fun `claimForProcessing - 이미 PROCESSING 이벤트는 false를 반환하고 상태가 유지된다`() {
        val event = fixture()
        insertAccount(event.accountId)

        repo.insertIfAbsent(event)
        repo.claimForProcessing(event.eventId)          // 첫 번째 claim

        val secondClaim = repo.claimForProcessing(event.eventId)

        assertFalse(secondClaim)
        assertEquals(EventStatus.PROCESSING, repo.findByEventId(event.eventId)!!.status)
    }

    @Test
    fun `claimForProcessing - DONE 이벤트는 false를 반환하고 DONE 상태가 유지된다`() {
        val event = fixture()
        val now = Instant.now()
        insertAccount(event.accountId)

        repo.insertIfAbsent(event)
        repo.claimForProcessing(event.eventId)
        repo.updateStatus(event.eventId, EventStatus.DONE, now, null)

        val result = repo.claimForProcessing(event.eventId)

        assertFalse(result)
        assertEquals(EventStatus.DONE, repo.findByEventId(event.eventId)!!.status)
    }

    @Test
    fun `claimForProcessing - FAILED 이벤트는 false를 반환하고 FAILED 상태가 유지된다`() {
        val event = fixture()
        val now = Instant.now()
        insertAccount(event.accountId)

        repo.insertIfAbsent(event)
        repo.claimForProcessing(event.eventId)
        repo.updateStatus(event.eventId, EventStatus.FAILED, now, "some error")

        val result = repo.claimForProcessing(event.eventId)

        assertFalse(result)
        assertEquals(EventStatus.FAILED, repo.findByEventId(event.eventId)!!.status)
    }

    // ── 5. updateStatus - DONE ────────────────────────────────────────────────

    @Test
    fun `updateStatus - PROCESSING 이벤트를 DONE으로 전이시킨다`() {
        val event = fixture()
        val processedAt = Instant.now()
        insertAccount(event.accountId)

        repo.insertIfAbsent(event)
        repo.claimForProcessing(event.eventId)

        repo.updateStatus(event.eventId, EventStatus.DONE, processedAt, null)

        val updated = repo.findByEventId(event.eventId)!!
        assertEquals(EventStatus.DONE, updated.status)
        assertNull(updated.errorMessage)
    }

    // ── 6. updateStatus - FAILED ──────────────────────────────────────────────

    @Test
    fun `updateStatus - PROCESSING 이벤트를 FAILED로 전이시키고 errorMessage를 저장한다`() {
        val event = fixture()
        val errorMsg    = "Account already exists"
        val processedAt = Instant.now()
        insertAccount(event.accountId)

        repo.insertIfAbsent(event)
        repo.claimForProcessing(event.eventId)

        repo.updateStatus(event.eventId, EventStatus.FAILED, processedAt, errorMsg)

        val updated = repo.findByEventId(event.eventId)!!
        assertEquals(EventStatus.FAILED, updated.status)
        assertEquals(errorMsg, updated.errorMessage)
    }

    // ── 7. 동시성/경합 최소 검증 ──────────────────────────────────────────────

    @Test
    fun `claimForProcessing - 동일 이벤트에 연속 두 번 호출 시 첫 번째만 true를 반환한다`() {
        val event = fixture()
        insertAccount(event.accountId)

        repo.insertIfAbsent(event)

        val first  = repo.claimForProcessing(event.eventId)
        val second = repo.claimForProcessing(event.eventId)

        assertTrue(first)
        assertFalse(second)
        assertEquals(EventStatus.PROCESSING, repo.findByEventId(event.eventId)!!.status)
    }


    private fun insertAccount(accountId: String) {
        DatabaseFactory.transaction {
            it.prepareStatement(
                """
            INSERT OR IGNORE INTO accounts (account_id, name, email, status, created_at, updated_at)
            VALUES (?, ?, ?, 'ACTIVE', ?, ?)
            """.trimIndent()
            ).use { ps ->
                ps.setString(1, accountId)
                ps.setString(2, "Test User")
                ps.setString(3, "test-$accountId@example.com")
                val now = "2024-01-01T00:00:00Z"
                ps.setString(4, now)
                ps.setString(5, now)
                ps.executeUpdate()
            }
        }
    }
}
