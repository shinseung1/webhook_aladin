package com.webhook.infrastructure.persistence

import com.webhook.domain.model.Account
import com.webhook.domain.model.AccountStatus
import com.webhook.domain.service.WebhookBusinessException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.time.Instant

class SqliteAccountRepository : AccountRepository {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class AccountPayload(
        val name: String,
        val email: String,
        val status: String
    )

    // ─────────────────────────────────────────────────────────────
    // upsert
    // ─────────────────────────────────────────────────────────────
    override fun upsert(conn: Connection, accountId: String, rawPayload: String, updatedAt: Instant) {
        val payload = try {
            json.decodeFromString<AccountPayload>(rawPayload)
        } catch (e: SerializationException) {
            throw WebhookBusinessException("Invalid payload: ${e.message}")
        }

        if (payload.name.isBlank()) throw WebhookBusinessException("Payload missing field: name")
        if (payload.email.isBlank()) throw WebhookBusinessException("Payload missing field: email")

        val status = try {
            AccountStatus.valueOf(payload.status)
        } catch (_: IllegalArgumentException) {
            throw WebhookBusinessException("Invalid account status: ${payload.status}")
        }

        conn.prepareStatement(
            """
            INSERT INTO accounts (account_id, name, email, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(account_id) DO UPDATE SET
                name       = excluded.name,
                email      = excluded.email,
                status     = excluded.status,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, accountId)
            stmt.setString(2, payload.name)
            stmt.setString(3, payload.email)
            stmt.setString(4, status.name)
            stmt.setString(5, updatedAt.toString())
            stmt.setString(6, updatedAt.toString())
            stmt.executeUpdate()
        }
    }

    override fun upsert(accountId: String, rawPayload: String, updatedAt: Instant) {
        DatabaseFactory.transaction { conn ->
            upsert(conn, accountId, rawPayload, updatedAt)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // deleteOrClose (요구사항: DELETED로 변경)
    // ─────────────────────────────────────────────────────────────
    override fun deleteOrClose(conn: Connection, accountId: String, updatedAt: Instant) {
        val updated = conn.prepareStatement(
            "UPDATE accounts SET status = ?, updated_at = ? WHERE account_id = ?"
        ).use { stmt ->
            stmt.setString(1, AccountStatus.DELETED.name)
            stmt.setString(2, updatedAt.toString())
            stmt.setString(3, accountId)
            stmt.executeUpdate()   // ✅ 영향 row 수 반환
        }

        if (updated == 0) {
            throw WebhookBusinessException("Account not found: $accountId")
        }
    }

    override fun deleteOrClose(accountId: String, updatedAt: Instant) {
        DatabaseFactory.transaction { conn ->
            deleteOrClose(conn, accountId, updatedAt)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // findById
    // ─────────────────────────────────────────────────────────────
    override fun findById(conn: Connection, accountId: String): Account? {
        conn.prepareStatement(
            """
            SELECT account_id, name, email, status, created_at, updated_at
            FROM accounts
            WHERE account_id = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, accountId)
            stmt.executeQuery().use { rs ->
                return if (rs.next()) {
                    Account(
                        accountId = rs.getString("account_id"),
                        name = rs.getString("name"),
                        email = rs.getString("email"),
                        status = AccountStatus.valueOf(rs.getString("status")),
                        createdAt = Instant.parse(rs.getString("created_at")),
                        updatedAt = Instant.parse(rs.getString("updated_at"))
                    )
                } else null
            }
        }
    }

    override fun findById(accountId: String): Account? {
        return DatabaseFactory.transaction { conn ->
            findById(conn, accountId)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // updateEmailForwarding
    // ─────────────────────────────────────────────────────────────
    override fun updateEmailForwarding(conn: Connection, accountId: String, rawPayload: String, updatedAt: Instant) {
        val newEmail = extractEmailFromRawPayload(rawPayload)
            ?: throw WebhookBusinessException("Payload missing field: email")
        if (newEmail.isBlank()) throw WebhookBusinessException("Payload missing field: email")

        conn.prepareStatement(
            """
            UPDATE accounts
            SET email = ?, updated_at = ?
            WHERE account_id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, newEmail)
            ps.setString(2, updatedAt.toString())
            ps.setString(3, accountId)
            ps.executeUpdate()
        }

        conn.createStatement().use { st ->
            st.executeQuery("SELECT changes()").use { rs ->
                rs.next()
                if (rs.getInt(1) == 0) throw WebhookBusinessException("Account not found: $accountId")
            }
        }
    }

    override fun updateEmailForwarding(accountId: String, rawPayload: String, updatedAt: Instant) {
        DatabaseFactory.transaction { conn ->
            updateEmailForwarding(conn, accountId, rawPayload, updatedAt)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // markAppleDeleted
    // ─────────────────────────────────────────────────────────────
    override fun markAppleDeleted(conn: Connection, accountId: String, updatedAt: Instant) {
        conn.prepareStatement(
            """
            UPDATE accounts
            SET status = ?, updated_at = ?
            WHERE account_id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, AccountStatus.APPLE_DELETED.name)
            ps.setString(2, updatedAt.toString())
            ps.setString(3, accountId)
            ps.executeUpdate()
        }

        conn.createStatement().use { st ->
            st.executeQuery("SELECT changes()").use { rs ->
                rs.next()
                if (rs.getInt(1) == 0) throw WebhookBusinessException("Account not found: $accountId")
            }
        }
    }

    override fun markAppleDeleted(accountId: String, updatedAt: Instant) {
        DatabaseFactory.transaction { conn ->
            markAppleDeleted(conn, accountId, updatedAt)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // email extract
    // ─────────────────────────────────────────────────────────────
    private fun extractEmailFromRawPayload(rawPayload: String): String? {
        val root: JsonElement = try {
            json.parseToJsonElement(rawPayload)
        } catch (_: Exception) {
            return null
        }
        val obj = root as? JsonObject ?: return null

        fun pick(o: JsonObject): String? =
            o["email"]?.jsonPrimitive?.contentOrNull
                ?: o["forwardingEmail"]?.jsonPrimitive?.contentOrNull

        pick(obj)?.let { return it }
        val payloadObj = obj["payload"] as? JsonObject ?: return null
        return pick(payloadObj)
    }
}