package com.icuplayground

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** Max bytes for the template / args payload — a basic guard for public hosting (§11). */
private const val MAX_TEMPLATE_LEN = 100_000
private const val MAX_ARGS_LEN = 200_000

fun Route.formatRoutes() {
    post("/api/format") {
        val req = call.receive<FormatRequest>()

        if (req.template.length > MAX_TEMPLATE_LEN) {
            call.respond(
                FormatResponse(
                    error = FormatError(ErrorType.INTERNAL, "Template too large (max $MAX_TEMPLATE_LEN chars)"),
                ),
            )
            return@post
        }
        if (req.args.toString().length > MAX_ARGS_LEN) {
            call.respond(
                FormatResponse(
                    error = FormatError(ErrorType.INTERNAL, "Arguments too large (max $MAX_ARGS_LEN chars)"),
                ),
            )
            return@post
        }

        // Errors are returned as HTTP 200 + error body (§5): in a playground,
        // an error is a normal result the frontend renders inline.
        call.respond(HttpStatusCode.OK, Renderer.renderGuarded(req))
    }

    get("/api/locales") {
        call.respond(Locales.list)
    }
}
