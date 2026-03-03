package com.webhook.config

data class AppConfig(
    val webhookSecret: String,
    val dbPath: String,
    val port: Int
) {
    companion object {
        fun load(): AppConfig {
            val secret = System.getenv("WEBHOOK_SECRET")
                ?: throw IllegalStateException("WEBHOOK_SECRET environment variable is not set")
            val dbPath = System.getenv("WEBHOOK_DB_PATH") ?: "webhook.db"
            val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
            return AppConfig(webhookSecret = secret, dbPath = dbPath, port = port)
        }
    }
}