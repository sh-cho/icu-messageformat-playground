package com.joebrothers.icuplayground

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

    // Build-time pass (native: reflection tracing; JVM: AppCDS via -XX:ArchiveClassesAtExit),
    // kept in Kotlin so the Dockerfiles need not spin up a server and curl it.
    if (System.getProperty("headless.warmup") == "true") {
        headlessWarmup()
        return
    }

    embeddedServer(CIO, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

/**
 * Drives the reflection the running server would (icu4j formatters + the kotlinx
 * serializers Ktor resolves per route response type) without binding a socket, so
 * native-image can trace it statically. Reflection is locale-independent, so en-US
 * is enough here.
 */
private fun headlessWarmup() {
    warmIcu()

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun trace(type: KType, value: Any?) {
        // serializer(KType) mirrors Ktor's lookup; the round-trip forces the whole graph.
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
    trace(typeOf<MetaResponse>(), MetaResponse("x", "x", "x", listOf(EngineInfo("mf1", "x")), 0, "x", "x", "x", "JVM", "x"))
}

/** Build-time only: exercises both engines to trigger icu4j class loading and reflective lookups. */
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

    // Lets the Vite dev server (:5173) call the API in development.
    install(CORS) {
        anyHost()
        allowHeader(io.ktor.http.HttpHeaders.ContentType)
    }

    // Read index.html explicitly rather than staticResources(...) { default("index.html") }:
    // in a GraalVM native image the "static" resource dir returns a listing instead of null,
    // so that fallback never fires and "/" would serve the listing.
    val indexHtml = object {}.javaClass.classLoader.getResourceAsStream("static/index.html")?.readBytes()

    routing {
        // Liveness/readiness probe for orchestrators (slim images have no shell for a Docker HEALTHCHECK).
        get("/api/health") { call.respondText("ok") }

        formatRoutes()
        metaRoutes()
        staticResources("/assets", "static/assets")
        // SPA shell for the root and any non-API path (null in pure backend dev runs).
        if (indexHtml != null) {
            get("/{path...}") { call.respondBytes(indexHtml, ContentType.Text.Html) }
        }
    }
}
