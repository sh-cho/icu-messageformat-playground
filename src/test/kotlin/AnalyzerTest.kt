package com.icuplayground

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun analyze(template: String, locale: String): FormatResponse =
    Renderer.render(
        FormatRequest(Engine.MF1, template, locale, Json.parseToJsonElement("{}") as JsonObject),
    )

class AnalyzerTest {

    @Test
    fun `detects arguments with inferred types`() {
        val r = analyze(
            "{count, plural, one {# item} other {# items}} for {name} on {d, date, short}",
            "en-US",
        )
        val byName = r.detectedArgs.associate { it.name to it.type }
        assertEquals("number", byName["count"])
        assertEquals("string", byName["name"])
        assertEquals("date", byName["d"])
    }

    @Test
    fun `english plural fully covered`() {
        val r = analyze("{count, plural, one {# item} other {# items}}", "en-US")
        val c = r.pluralChecks.single()
        assertEquals("count", c.argName)
        assertEquals(listOf("one", "other"), c.required)
        assertTrue(c.missing.isEmpty(), "expected no missing, got ${c.missing}")
    }

    @Test
    fun `polish plural missing few and many`() {
        val r = analyze("{count, plural, one {# rzecz} other {# rzeczy}}", "pl-PL")
        val c = r.pluralChecks.single()
        assertTrue("few" in c.missing, "missing should contain few: ${c.missing}")
        assertTrue("many" in c.missing, "missing should contain many: ${c.missing}")
    }

    @Test
    fun `explicit equals selector covers the english one category`() {
        // =1 covers English 'one' (whose only sample is 1), so it's not missing.
        val r = analyze("{count, plural, =1 {one thing} other {# things}}", "en-US")
        val c = r.pluralChecks.single()
        assertTrue(c.missing.isEmpty(), "expected no missing, got ${c.missing}")
    }

    @Test
    fun `selectordinal uses ordinal rules`() {
        val r = analyze(
            "{pos, selectordinal, one {#st} two {#nd} few {#rd} other {#th}}",
            "en-US",
        )
        val c = r.pluralChecks.single()
        assertEquals("selectordinal", c.type)
        assertTrue(c.missing.isEmpty(), "expected no missing, got ${c.missing}")
    }

    @Test
    fun `mf2 is not analyzed`() {
        val r = Renderer.render(
            FormatRequest(
                Engine.MF2,
                ".input {\$count :number}\n.match \$count\n1 {{one}}\n* {{many}}",
                "en-US",
                Json.parseToJsonElement("{}") as JsonObject,
            ),
        )
        assertTrue(r.pluralChecks.isEmpty())
        assertTrue(r.detectedArgs.isEmpty())
    }
}
