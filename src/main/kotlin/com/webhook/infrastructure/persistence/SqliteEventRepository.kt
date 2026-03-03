package com.webhook.infrastructure.persistence

import com.webhook.domain.model.EventStatus
import com.webhook.domain.model.EventType
import com.webhook.domain.model.WebhookEvent
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant

class SqliteEventRepository (
    private val jdbcUrl: String = "jdbc:sqlite:${System.getenv("WEBHOOK_DB_PATH") ?: "webhook.db"}"
) : EventRepository {

    override fun insertIfAbsent(conn: Connection, event: WebhookEvent): Boolean =
        DatabaseFactory.transaction { conn ->
            conn.prepareStatement(
                """
                INSERT OR IGNORE INTO events
                    (event_id, event_type, account_id, raw_payload, status,
                     created_at, processed_at, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, event.eventId)
                stmt.setString(2, event.eventType.name)
                stmt.setString(3, event.accountId)
                stmt.setString(4, event.rawPayload)
                stmt.setString(5, event.status.name)
                stmt.setString(6, event.createdAt.toString())
                if (event.processedAt != null) stmt.setString(7, event.processedAt.toString())
                else stmt.setNull(7, Types.VARCHAR)
                if (event.errorMessage != null) stmt.setString(8, event.errorMessage)
                else stmt.setNull(8, Types.VARCHAR)
                stmt.executeUpdate()
            }
            conn.createStatement().use { st ->
                st.executeQuery("SELECT changes()").use { rs ->
                    rs.next(); rs.getInt(1) == 1
                }
            }
        }

    override fun insertIfAbsent(event: WebhookEvent): Boolean =
        DatabaseFactory.transaction { conn -> insertIfAbsent(conn, event) }

    override fun findByEventId(conn: Connection, eventId: String): WebhookEvent? =
        DatabaseFactory.transaction { conn ->
            conn.prepareStatement(
                """
                SELECT event_id, event_type, account_id, raw_payload, status,
                       created_at, processed_at, error_message
                FROM events
                WHERE event_id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, eventId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) mapRow(rs) else null
                }
            }
        }
    override fun findByEventId(eventId: String): WebhookEvent? =
        DatabaseFactory.transaction { conn -> findByEventId(conn, eventId) }

    override fun claimForProcessing(conn: Connection, eventId: String): Boolean =
        DatabaseFactory.transaction { conn ->
            conn.prepareStatement(
                "UPDATE events SET status = 'PROCESSING' WHERE event_id = ? AND status = 'RECEIVED'"
            ).use { stmt ->
                stmt.setString(1, eventId)
                stmt.executeUpdate()
            }
            conn.createStatement().use { st ->
                st.executeQuery("SELECT changes()").use { rs ->
                    rs.next(); rs.getInt(1) == 1
                }
            }
        }

    override fun claimForProcessing(eventId: String): Boolean =
        DatabaseFactory.transaction { conn -> claimForProcessing(conn,eventId) }


    override fun updateStatus(conn: Connection, eventId: String, status: EventStatus, processedAt: Instant?, errorMessage: String?) {
        DatabaseFactory.transaction { conn ->
            conn.prepareStatement(
                "UPDATE events SET status = ?, processed_at = ?, error_message = ? WHERE event_id = ?"
            ).use { stmt ->
                stmt.setString(1, status.name)
                if (processedAt != null) stmt.setString(2, processedAt.toString())
                else stmt.setNull(2, Types.VARCHAR)
                if (errorMessage != null) stmt.setString(3, errorMessage)
                else stmt.setNull(3, Types.VARCHAR)
                stmt.setString(4, eventId)
                stmt.executeUpdate()
            }
        }
    }
    override fun updateStatus(
        eventId: String,
        status: EventStatus,
        processedAt: Instant?,
        errorMessage: String?
    ) {
        DatabaseFactory.transaction { conn ->
            updateStatus(conn, eventId, status, processedAt, errorMessage)
        }
    }

    private fun mapRow(rs: ResultSet): WebhookEvent = WebhookEvent(
        eventId      = rs.getString("event_id"),
        eventType    = EventType.valueOf(rs.getString("event_type")),
        accountId    = rs.getString("account_id"),
        rawPayload   = rs.getString("raw_payload"),
        status       = EventStatus.valueOf(rs.getString("status")),
        createdAt    = Instant.parse(rs.getString("created_at")),
        processedAt  = rs.getString("processed_at")?.let { Instant.parse(it) },
        errorMessage = rs.getString("error_message")
    )
}