package com.webhook.presentation

import com.webhook.application.AccountQueryResult
import com.webhook.application.AccountQueryUseCase
import com.webhook.presentation.dto.AccountResponse
import com.webhook.presentation.dto.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.accountQueryRoutes(useCase: AccountQueryUseCase) {
    get("/accounts/{accountId}") {
        val accountId = call.parameters["accountId"]
        if (accountId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid accountId"))
            return@get
        }

        when (val result = useCase.get(accountId)) {
            is AccountQueryResult.Success  -> call.respond(HttpStatusCode.OK, AccountResponse.from(result.account))
            is AccountQueryResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
            is AccountQueryResult.Failure  -> call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal error"))
        }
    }
}