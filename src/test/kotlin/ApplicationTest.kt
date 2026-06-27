package com.icuplayground

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {

    @Test
    fun `format endpoint returns rendered output`() = testApplication {
        application { module() }
        val res = client.post("/api/format") {
            contentType(ContentType.Application.Json)
            setBody("""{"engine":"mf1","template":"{price, number, ::currency/USD}","locale":"en-US","args":{"price":1234.5}}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue("\$1,234.50" in body, "body was: $body")
    }

    @Test
    fun `errors are returned as HTTP 200 with an error body`() = testApplication {
        application { module() }
        val res = client.post("/api/format") {
            contentType(ContentType.Application.Json)
            setBody("""{"engine":"mf1","template":"{count, plural, one {# ","locale":"en-US","args":{"count":1}}""")
        }
        // §5: errors are normal flow — HTTP 200, never 4xx.
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue("SYNTAX" in res.bodyAsText())
    }

    @Test
    fun `format-all renders one message across many locales`() = testApplication {
        application { module() }
        val res = client.post("/api/format-all") {
            contentType(ContentType.Application.Json)
            setBody("""{"engine":"mf1","template":"{p, number, ::currency/USD}","args":{"p":1234.5}}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue("en-US" in body, "missing en-US: $body")
        assertTrue("ko-KR" in body, "missing ko-KR: $body")
        assertTrue("\$1,234.50" in body, "missing US currency render: $body")
    }

    @Test
    fun `locales endpoint lists locales`() = testApplication {
        application { module() }
        val res = client.get("/api/locales")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue("ko-KR" in res.bodyAsText())
    }
}
