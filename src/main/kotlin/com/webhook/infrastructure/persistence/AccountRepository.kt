package com.webhook.infrastructure.persistence

import com.webhook.domain.model.Account
import java.sql.Connection
import java.time.Instant

interface AccountRepository {
    fun findById(accountId: String): Account?
    fun findById(conn: Connection, accountId: String): Account?

    /** 계정을 신규 삽입하거나 기존 레코드를 갱신한다. */
    fun upsert(accountId: String, rawPayload: String, updatedAt: Instant)
    fun upsert(conn: Connection, accountId: String, rawPayload: String, updatedAt: Instant)

    /**
     * 계정을 비활성화한다.
     * 구현체는 물리 삭제 또는 상태를 DELETED 변경하는 두 가지 방식 중 택1할 수 있다.
     * 어느 방식이든 이후 findById()는 null 또는 DELETED 계정을 반환해야 한다.
     */
    fun deleteOrClose(accountId: String, deletedAt: Instant): Unit
    fun deleteOrClose(conn: Connection, accountId: String, deletedAt: Instant): Unit

    fun updateEmailForwarding(accountId: String, rawPayload: String, occurredAt: Instant)
    fun updateEmailForwarding(conn: Connection,accountId: String, rawPayload: String, occurredAt: Instant)

    fun markAppleDeleted(accountId: String, occurredAt: Instant)
    fun markAppleDeleted(conn: Connection,accountId: String, occurredAt: Instant)
}