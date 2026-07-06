package com.joebrothers.icuplayground

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

// Payload guards for public hosting.
private const val MAX_TEMPLATE_LEN = 100_000
private const val MAX_ARGS_LEN = 200_000

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

        // Errors render inline in the playground, so return them as HTTP 200 + error body, never 4xx.
        call.respond(HttpStatusCode.OK, Renderer.renderGuarded(req))
    }

    // Renders the same template/args across every dropdown locale; the request's `locale` is ignored.
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

    post("/api/prettify") {
        val req = call.receive<FormatRequest>()
        call.respond(PrettifyResponse(IcuPrettyPrinter.prettify(req.template, req.engine)))
    }

    get("/api/locales") {
        call.respond(Locales.list)
    }
}
