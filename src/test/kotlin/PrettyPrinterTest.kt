package com.icuplayground

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun render(template: String, locale: String, args: String): String? =
    Renderer.render(
        FormatRequest(Engine.MF1, template, locale, Json.parseToJsonElement(args) as JsonObject),
    ).output

class PrettyPrinterTest {

    @Test
    fun `formats plural onto multiple lines`() {
        val src = "{count, plural, =0 {none} one {# item} other {# items}}"
        val pretty = IcuPrettyPrinter.prettify(src, Engine.MF1)
        assertTrue(pretty.contains("\n"), "expected multiline output:\n$pretty")
        for (n in listOf(0, 1, 5)) {
            val args = """{ "count": $n }"""
            assertEquals(render(src, "en-US", args), render(pretty, "en-US", args))
        }
    }

    @Test
    fun `formats nested select and plural identically`() {
        val src =
            "{g, select, female {She has {n, plural, one {# cat} other {# cats}}} other {They have {n, plural, one {# cat} other {# cats}}}}"
        val pretty = IcuPrettyPrinter.prettify(src, Engine.MF1)
        for (n in listOf(1, 3)) {
            val args = """{ "g": "female", "n": $n }"""
            assertEquals(render(src, "en-US", args), render(pretty, "en-US", args))
        }
    }

    @Test
    fun `preserves literal braces and apostrophes`() {
        // '{' is a literal brace; '' is a literal apostrophe.
        val src = "It''s here: '{'literal'}' {count, plural, one {# x} other {# y}}"
        val pretty = IcuPrettyPrinter.prettify(src, Engine.MF1)
        val args = """{ "count": 1 }"""
        assertEquals(render(src, "en-US", args), render(pretty, "en-US", args))
    }

    @Test
    fun `mf2 is returned unchanged`() {
        val src = ".input {\$count :number}\n.match \$count\n1 {{one}}\n* {{many}}"
        assertEquals(src, IcuPrettyPrinter.prettify(src, Engine.MF2))
    }

    @Test
    fun `invalid template returned unchanged`() {
        val src = "{count, plural, one {# "
        assertEquals(src, IcuPrettyPrinter.prettify(src, Engine.MF1))
    }
}
