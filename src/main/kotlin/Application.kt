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
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    // Warm icu4j before serving — but only on the JVM. There it front-loads CLDR
    // parsing, class loading and JIT (and the Docker build captures them into the
    // AppCDS archive via -Dwarmup.exit). A native image has no JIT and no class
    // loading, so the only thing left to warm is icu4j's lazy CLDR parsing — and for
    // a scale-to-zero deploy that merely relocates the cost from request #1 to
    // startup (same time-to-first-response), so we skip it for a leaner cold start.
    // The property is "runtime" only inside a running native image; null on the JVM.
    val nativeRuntime = System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime"
    if (!nativeRuntime) warmIcu()
    // CDS training run (see Dockerfile): warm icu4j, then exit cleanly so the JVM
    // dumps the AppCDS archive instead of starting a server that never returns.
    if (System.getProperty("warmup.exit") == "true") return
    embeddedServer(CIO, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

/**
 * Touch both engines across the main format types (plural, number, date, select) so
 * icu4j's class loading + CLDR init + the reflective formatter lookups all happen at
 * boot, not on request #1. Doubles as coverage for the native-image tracing agent
 * (Dockerfile.native), which records the reflection these paths trigger.
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
