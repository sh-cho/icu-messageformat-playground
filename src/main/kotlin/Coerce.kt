package com.icuplayground

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Date

/** Thrown when an argument value cannot be mapped to a Java type icu4j accepts. */
class CoercionException(val type: ErrorType, message: String) : Exception(message)

/**
 * Converts JSON argument values into the Java types icu4j MessageFormat expects.
 *
 * JSON has no date type, so dates use an explicit tagging convention:
 *   { "exp": { "@type": "date", "value": "2025-03-27T00:00:00Z" } }
 *
 * Integer vs. floating-point distinction is preserved because it affects `plural` selection.
 */
fun coerce(args: JsonObject): Map<String, Any?> =
    args.mapValues { (key, value) -> coerceValue(key, value) }

private fun coerceValue(key: String, el: JsonElement): Any? = when (el) {
    is JsonNull -> null
    is JsonPrimitive -> coercePrimitive(el)
    is JsonObject -> coerceTagged(key, el)
    is JsonArray -> el.map { coerceValue(key, it) }
}

private fun coercePrimitive(el: JsonPrimitive): Any {
    if (el.isString) return el.content
    el.booleanOrNull?.let { return it.toString() } // select branches match on strings
    el.longOrNull?.let { return it }               // keep integers as Long (plural: one vs other)
    el.doubleOrNull?.let { return it }
    return el.content
}

private fun coerceTagged(key: String, obj: JsonObject): Any? {
    val typeEl = obj["@type"]
    if (typeEl == null) {
        // Not a tagged value — treat as a nested map (rare for ICU args).
        return obj.mapValues { (k, v) -> coerceValue(k, v) }
    }
    val type = (typeEl as? JsonPrimitive)?.content
    val valueEl = obj["value"]
        ?: throw CoercionException(ErrorType.TYPE_MISMATCH, "Tagged argument '$key' is missing a \"value\" field")

    return when (type) {
        "date", "time", "datetime" -> parseDate(key, valueEl)
        "number" -> parseNumber(key, valueEl)
        "string" -> (valueEl as? JsonPrimitive)?.content ?: valueEl.toString()
        "boolean" -> (valueEl as? JsonPrimitive)?.content ?: valueEl.toString()
        else -> throw CoercionException(
            ErrorType.TYPE_MISMATCH,
            "Unknown @type \"$type\" for argument '$key' (expected date/time/datetime/number/string/boolean)",
        )
    }
}

private fun parseNumber(key: String, el: JsonElement): Number {
    val prim = el as? JsonPrimitive
        ?: throw CoercionException(ErrorType.TYPE_MISMATCH, "Argument '$key' tagged number must be a primitive")
    prim.longOrNull?.let { return it }
    prim.doubleOrNull?.let { return it }
    return prim.content.toLongOrNull()
        ?: prim.content.toDoubleOrNull()
        ?: throw CoercionException(ErrorType.TYPE_MISMATCH, "Argument '$key' value \"${prim.content}\" is not a number")
}

private fun parseDate(key: String, el: JsonElement): Date {
    val prim = el.let { it as? JsonPrimitive }
        ?: throw CoercionException(ErrorType.TYPE_MISMATCH, "Argument '$key' tagged date must be a string or epoch-millis number")

    if (!prim.isString) {
        prim.longOrNull?.let { return Date(it) }
    }
    val s = prim.content.trim()

    // Epoch millis as a string.
    s.toLongOrNull()?.let { return Date(it) }

    // ISO-8601 in a few common shapes, all normalised to an instant.
    runCatching { return Date.from(Instant.parse(s)) }
    runCatching { return Date.from(OffsetDateTime.parse(s).toInstant()) }
    runCatching { return Date.from(LocalDateTime.parse(s).toInstant(ZoneOffset.UTC)) }
    runCatching { return Date.from(LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant()) }

    throw CoercionException(
        ErrorType.TYPE_MISMATCH,
        "Argument '$key' value \"$s\" is not a valid date (use ISO-8601, e.g. 2025-03-27T00:00:00Z, or epoch millis)",
    )
}
