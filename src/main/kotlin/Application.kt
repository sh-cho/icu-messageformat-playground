package com.icuplayground

import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import kotlin.reflect.KType
import kotlin.reflect.typeOf

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    // Build-time headless pass (no server, no sockets, no HTTP). The native build runs
    // it under the tracing agent to capture reflection metadata; the JVM build runs it
    // under -XX:ArchiveClassesAtExit to populate the AppCDS archive. Because "what to
    // trace" lives here in type-safe Kotlin, neither Dockerfile has to spin up a server
    // and curl hardcoded URLs to exercise the reflective paths.
    if (System.getProperty("headless.warmup") == "true") {
        headlessWarmup()
        return
    }

    // No warm-up before serving: AppCDS already covers JVM class loading, and icu4j's
    // remaining lazy CLDR parsing only relocates from request #1 to startup (same
    // time-to-first-response on scale-to-zero). Warming a live instance is a deployment
    // concern — e.g. a k8s startup/readiness probe hitting /api/format — not app logic.
    embeddedServer(CIO, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

/**
 * Headless build-time pass: drives the same reflection the running server would, but
 * without binding a socket. Covers (1) icu4j's formatter reflection via [warmIcu] and
 * (2) the kotlinx.serialization serializers Ktor resolves *reflectively* from each
 * route's response type — the part native-image cannot discover by static analysis.
 * Per-locale CLDR *data* is handled separately by the committed resource-config glob,
 * so en-US here is enough (reflection is locale-independent). Used by both the native
 * tracing agent and the JVM AppCDS training run — see the Dockerfiles.
 */
private fun headlessWarmup() {
    warmIcu()

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun trace(type: KType, value: Any?) {
        // serializer(KType) is exactly the reflective lookup Ktor performs; the
        // round-trip then forces the whole serializer graph (nested models, enums).
        @Suppress("UNCHECKED_CAST")
        val ser = serializer(type) as KSerializer<Any?>
        json.decodeFromString(ser, json.encodeToString(ser, value))
    }

    val resp = FormatResponse(
        output = "x",
        error = FormatError(ErrorType.SYNTAX, "m", 0),
        detectedArgs = listOf(ArgInfo("n", "number")),
        pluralChecks = listOf(PluralCheck("n", "plural", listOf("other"), listOf("other"), emptyList())),
    )
    trace(typeOf<FormatRequest>(), FormatRequest(template = "x"))
    trace(typeOf<FormatResponse>(), resp)
    trace(typeOf<List<LocaleInfo>>(), Locales.list)
    trace(typeOf<List<LocaleResult>>(), listOf(LocaleResult("en-US", "English", resp.output, resp.error, resp.pluralChecks)))
    trace(typeOf<PrettifyResponse>(), PrettifyResponse("x"))
}

/**
 * Exercises both engines across the main format types (plural, number, date, select)
 * so icu4j's class loading, CLDR init and reflective formatter lookups are all
 * triggered. Build-time only — invoked by [headlessWarmup] under the native tracing
 * agent and the JVM AppCDS training run. It is intentionally NOT run before serving
 * (see [main]).
 */
private fun warmIcu() {
    fun warm(engine: Engine, t: String, args: kotlinx.serialization.json.JsonObject) =
        runCatching { Renderer.render(FormatRequest(engine, t, "en-US", args)) }

    warm(Engine.MF1, "{n, plural, one {# item} other {# items}}", buildJsonObject { put("n", 2) })
    warm(Engine.MF1, "{x, number, percent} / {x, number}", buildJsonObject { put("x", 0.5) })
    warm(Engine.MF1, "{d, date, long} {d, time, short}", buildJsonObject { put("d", 0) })
    warm(Engine.MF1, "{g, select, male {he} female {she} other {they}}", buildJsonObject { put("g", "other") })
    warm(Engine.MF2, "Hello {\$name}!", buildJsonObject { put("name", "world") })
    warm(Engine.MF2, "{\$n :number} / {\$d :date}", buildJsonObject { put("n", 3); put("d", 0) })
}

fun Application.module() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
        )
    }

    // Allows the Vite dev server (:5173) to call the API during development.
    install(CORS) {
        anyHost()
        allowHeader(io.ktor.http.HttpHeaders.ContentType)
    }

    // Built frontend bundle (frontend/dist copied to resources/static by buildFrontend).
    // Loaded once from the classpath. We DON'T use `staticResources("/", "static") {
    // default("index.html") }`: in a GraalVM native image, opening the "static"
    // directory as a resource returns a directory listing (instead of null as on the
    // JVM), so the default("index.html") fallback never triggers and "/" serves the
    // listing. Reading index.html explicitly works identically in the jar and native.
    val indexHtml = object {}.javaClass.classLoader.getResourceAsStream("static/index.html")?.readBytes()

    routing {
        // Cheap liveness/readiness probe for orchestrators (k8s, load balancers,
        // Docker). Plain text so it needs no serialization — safe in the native image
        // and adds nothing to cold start. The slim runtime images have no shell/curl,
        // so a Docker HEALTHCHECK can't curl this; point orchestrator probes here instead.
        get("/api/health") { call.respondText("ok") }

        formatRoutes()
        // Hashed JS/CSS — plain file lookups, safe in both the jar and native image.
        staticResources("/assets", "static/assets")
        // Serve the SPA shell for the root and any other non-API path (absent in pure
        // backend dev runs, where indexHtml is null).
        if (indexHtml != null) {
            get("/{path...}") { call.respondBytes(indexHtml, ContentType.Text.Html) }
        }
    }
}
