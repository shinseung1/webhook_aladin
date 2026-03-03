package com.webhook.infrastructure.persistence

import java.sql.Connection
import java.sql.DriverManager

object DatabaseFactory {
    private const val DEFAULT_DB_PATH = "webhook.db"
    private fun defaultUrl() = "jdbc:sqlite:${System.getenv("WEBHOOK_DB_PATH") ?: DEFAULT_DB_PATH}"

    fun connect(jdbcUrl: String = defaultUrl()): Connection {
        runCatching { Class.forName("org.sqlite.JDBC") }
        val conn = DriverManager.getConnection(jdbcUrl)
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL;")
            stmt.execute("PRAGMA foreign_keys=ON;")
            stmt.execute("PRAGMA busy_timeout=5000;")
        }
        return conn
    }

//    fun initSchema(jdbcUrl: String = defaultUrl()) {
//        connect(jdbcUrl).use { conn ->
//            conn.createStatement().use { stmt ->
//                // ... 기존 동일
//            }
//        }
//    }

    fun initSchema(jdbcUrl: String = defaultUrl()) {
        // jdbcUrl을 쓰는 버전도 실제 스키마 생성 로직을 실행해야 함
        connect(jdbcUrl).use { conn ->
            conn.createStatement().use { stmt ->
                // accounts
                stmt.execute(
                    """
                CREATE TABLE IF NOT EXISTS accounts (
                    account_id TEXT PRIMARY KEY,
                    name       TEXT NOT NULL,
                    email      TEXT NOT NULL,
                    status     TEXT NOT NULL DEFAULT 'ACTIVE'
                               CHECK(status IN ('ACTIVE','SUSPENDED','DELETED','APPLE_DELETED')),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                """.trimIndent()
                )
                // events
                stmt.execute(
                    """
                CREATE TABLE IF NOT EXISTS events (
                    event_id      TEXT PRIMARY KEY,
                    event_type    TEXT NOT NULL
                                  CHECK(event_type IN ('ACCOUNT_CREATED','ACCOUNT_UPDATED','ACCOUNT_DELETED','EMAIL_FORWARDING_CHANGED','APPLE_ACCOUNT_DELETED')),
                    account_id    TEXT NOT NULL
                                  REFERENCES accounts(account_id),
                    raw_payload   TEXT NOT NULL,
                    status        TEXT NOT NULL DEFAULT 'RECEIVED'
                                  CHECK(status IN ('RECEIVED','PROCESSING','DONE','FAILED')),
                    created_at    TEXT NOT NULL,
                    processed_at  TEXT,
                    error_message TEXT,

                    FOREIGN KEY (account_id)
                    REFERENCES accounts(account_id)
                    DEFERRABLE INITIALLY DEFERRED
                );
                """.trimIndent()
                )
                // indexes
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_events_status     ON events(status);")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_events_account_id ON events(account_id);")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_accounts_email    ON accounts(email);")
            }
        }
    }

    fun <T> transaction(block: (Connection) -> T): T {
        val conn = connect()
        conn.autoCommit = false
        try {
            val result = block(conn)
            conn.commit()
            return result
        } catch (e: Throwable) {
            try { conn.rollback() } catch (_: Throwable) {}
            throw e
        } finally {
            try { conn.close() } catch (_: Throwable) {}
        }
    }
}