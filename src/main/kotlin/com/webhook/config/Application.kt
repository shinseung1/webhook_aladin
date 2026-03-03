package com.webhook.config

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
import com.webhook.presentation.dto.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing

fun Application.module() {
    DatabaseFactory.initSchema()

    val eventRepo = SqliteEventRepository()
    val accountRepo = SqliteAccountRepository()
    val verifier = HmacSignatureVerifier()
    val processUseCase = WebhookProcessingUseCase(eventRepo, accountRepo)
    val eventQueryUseCase = EventQueryUseCase(eventRepo)
    val accountQueryUseCase = AccountQueryUseCase(accountRepo)

    install(ContentNegotiation) {
        json()
    }

    install(StatusPages) {
        exception<Throwable> { call, _ ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal error"))
        }
    }

    routing {
        webhookRoutes(processUseCase, verifier)
        eventQueryRoutes(eventQueryUseCase)
        accountQueryRoutes(accountQueryUseCase)
    }
}
