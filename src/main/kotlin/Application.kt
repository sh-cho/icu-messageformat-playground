package com.icuplayground

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    // Force icu4j to load its CLDR data and JIT the format paths before we start
    // accepting traffic, so the first real request isn't the one that pays for it.
    // On scale-to-zero hosts (Cloud Run etc.) the cold start already blocks the
    // first request, so we'd rather front-load this cost than serve a slow first hit.
    warmIcu()
    // CDS training run (see Dockerfile): warm icu4j, then exit cleanly so the JVM
    // dumps the AppCDS archive instead of starting a server that never returns.
    if (System.getProperty("warmup.exit") == "true") return
    embeddedServer(CIO, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

/** Touch both engines once so icu4j's class loading + CLDR init happens at boot, not on request #1. */
private fun warmIcu() {
    runCatching {
        val sample = "{n, plural, one {# item} other {# items}}"
        Renderer.render(FormatRequest(Engine.MF1, sample, "en-US", buildJsonObject { put("n", 2) }))
        Renderer.render(FormatRequest(Engine.MF2, "Hello {\$name}!", "en-US", buildJsonObject { put("name", "world") }))
    }
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

    routing {
        formatRoutes()
        // Built frontend bundle (frontend/dist copied here by the buildFrontend task).
        // Serves index.html at "/" and static assets; absent in pure backend dev runs.
        staticResources("/", "static") {
            default("index.html")
        }
    }
}
