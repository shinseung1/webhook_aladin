package com.webhook.presentation

import com.webhook.application.EventQueryResult
import com.webhook.application.EventQueryUseCase
import com.webhook.presentation.dto.ErrorResponse
import com.webhook.presentation.dto.EventResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.eventQueryRoutes(useCase: EventQueryUseCase) {
    get("/events/{eventId}") {
        val eventId = call.parameters["eventId"]
        if (eventId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid eventId"))
            return@get
        }
        when (val result = useCase.get(eventId)) {
            is EventQueryResult.Success  -> call.respond(HttpStatusCode.OK, EventResponse.from(result.event))
            is EventQueryResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
            is EventQueryResult.Failure  -> call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal error"))
        }
    }
}