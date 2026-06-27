package com.icuplayground

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun argsOf(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

private fun render(
    template: String,
    locale: String = "en-US",
    args: String = "{}",
    engine: Engine = Engine.MF1,
): FormatResponse = Renderer.render(FormatRequest(engine, template, locale, argsOf(args)))

class RendererTest {

    // ---- MF1: the handoff §13 examples ------------------------------------

    @Test
    fun `plural exact zero ko`() {
        val r = render(
            "{count, plural, =0 {항목 없음} one {# 항목} other {# 항목}}",
            locale = "ko-KR",
            args = """{ "count": 0 }""",
        )
        assertNull(r.error, "unexpected error: ${r.error}")
        assertEquals("항목 없음", r.output)
    }

    @Test
    fun `plural other ko`() {
        val r = render(
            "{count, plural, =0 {항목 없음} one {# 항목} other {# 항목}}",
            locale = "ko-KR",
            args = """{ "count": 3 }""",
        )
        assertEquals("3 항목", r.output)
    }

    @Test
    fun `select gender female`() {
        val r = render(
            "{gender, select, male {그가} female {그녀가} other {그들이}} 사진을 올렸습니다.",
            locale = "ko-KR",
            args = """{ "gender": "female" }""",
        )
        assertEquals("그녀가 사진을 올렸습니다.", r.output)
    }

    @Test
    fun `currency skeleton en US`() {
        val r = render(
            "{price, number, ::currency/USD}",
            locale = "en-US",
            args = """{ "price": 1234.5 }""",
        )
        assertEquals("$1,234.50", r.output)
    }

    // ---- Coercion ---------------------------------------------------------

    @Test
    fun `tagged date renders`() {
        val r = render(
            "{exp, date, long}",
            locale = "en-US",
            args = """{ "exp": { "@type": "date", "value": "2025-03-27T00:00:00Z" } }""",
        )
        assertNull(r.error, "unexpected error: ${r.error}")
        assertNotNull(r.output)
        // long format includes the year; locale-specific wording varies by ICU version
        assert(r.output!!.contains("2025")) { "expected year in '${r.output}'" }
    }

    @Test
    fun `integer vs double affects plural`() {
        // 1.0 is not plural-"one" in English; "1" (Long) is.
        val one = render("{n, plural, one {one} other {many}}", args = """{ "n": 1 }""")
        assertEquals("one", one.output)
        val many = render("{n, plural, one {one} other {many}}", args = """{ "n": 2 }""")
        assertEquals("many", many.output)
    }

    @Test
    fun `bad date tag is type mismatch`() {
        val r = render(
            "{exp, date, short}",
            args = """{ "exp": { "@type": "date", "value": "not-a-date" } }""",
        )
        assertEquals(ErrorType.TYPE_MISMATCH, r.error?.type)
    }

    // ---- Errors -----------------------------------------------------------

    @Test
    fun `syntax error is reported`() {
        val r = render("{count, plural, one {# item} ")
        assertNull(r.output)
        assertEquals(ErrorType.SYNTAX, r.error?.type)
    }

    @Test
    fun `missing argument is reported`() {
        val r = render("{count, number}", args = "{}")
        assertNull(r.output)
        assertEquals(ErrorType.MISSING_ARG, r.error?.type)
    }

    // ---- MF2 (technical preview) -----------------------------------------

    @Test
    fun `mf2 match one`() {
        val r = render(
            """
            .input {${'$'}count :number}
            .match ${'$'}count
            1 {{알림이 1개 있습니다.}}
            * {{알림이 {${'$'}count}개 있습니다.}}
            """.trimIndent(),
            locale = "ko-KR",
            args = """{ "count": 1 }""",
            engine = Engine.MF2,
        )
        assertNull(r.error, "unexpected error: ${r.error}")
        assertEquals("알림이 1개 있습니다.", r.output)
    }

    @Test
    fun `mf2 match other`() {
        val r = render(
            """
            .input {${'$'}count :number}
            .match ${'$'}count
            1 {{알림이 1개 있습니다.}}
            * {{알림이 {${'$'}count}개 있습니다.}}
            """.trimIndent(),
            locale = "ko-KR",
            args = """{ "count": 5 }""",
            engine = Engine.MF2,
        )
        assertNull(r.error, "unexpected error: ${r.error}")
        assertEquals("알림이 5개 있습니다.", r.output)
    }
}
