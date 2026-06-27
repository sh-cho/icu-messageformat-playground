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

/** Returns an oversize error message, or null if the request is within limits. */
private fun oversize(req: FormatRequest): String? = when {
    req.template.length > MAX_TEMPLATE_LEN -> "Template too large (max $MAX_TEMPLATE_LEN chars)"
    req.args.toString().length > MAX_ARGS_LEN -> "Arguments too large (max $MAX_ARGS_LEN chars)"
    else -> null
}

fun Route.formatRoutes() {
    post("/api/format") {
        val req = call.receive<FormatRequest>()
        oversize(req)?.let {
            call.respond(FormatResponse(error = FormatError(ErrorType.INTERNAL, it)))
            return@post
        }

        // Errors are returned as HTTP 200 + error body (§5): in a playground,
        // an error is a normal result the frontend renders inline.
        call.respond(HttpStatusCode.OK, Renderer.renderGuarded(req))
    }

    // Renders the same template/args across every locale in the dropdown, for
    // the optional multi-locale comparison view. The request's `locale` is
    // ignored. (`engine`, `template`, `args` are used.)
    post("/api/format-all") {
        val req = call.receive<FormatRequest>()
        oversize(req)?.let {
            call.respond(
                listOf(LocaleResult("", "", error = FormatError(ErrorType.INTERNAL, it))),
            )
            return@post
        }

        val results = Locales.list.map { loc ->
            val r = Renderer.renderGuarded(req.copy(locale = loc.tag))
            LocaleResult(loc.tag, loc.displayName, r.output, r.error, r.pluralChecks)
        }
        call.respond(HttpStatusCode.OK, results)
    }

    get("/api/locales") {
        call.respond(Locales.list)
    }
}
