package com.icuplayground

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class Engine {
    @SerialName("mf1")
    MF1,

    @SerialName("mf2")
    MF2,
}

@Serializable
data class FormatRequest(
    val engine: Engine = Engine.MF1,
    val template: String,
    val locale: String = "en-US",
    /** Values follow the coercion rules in Coerce.kt. */
    val args: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class FormatResponse(
    val output: String? = null,
    val error: FormatError? = null,
    val detectedArgs: List<ArgInfo> = emptyList(),
    val pluralChecks: List<PluralCheck> = emptyList(),
)

@Serializable
data class ArgInfo(
    val name: String,
    /** "number" | "string" | "date" | "time" */
    val type: String,
)

/**
 * CLDR plural-category coverage for one plural/selectordinal argument. ICU does
 * not require categories beyond `other`, but omitting e.g. `few` for Polish
 * renders incorrectly — this surfaces what a translation is missing.
 */
@Serializable
data class PluralCheck(
    val argName: String,
    /** "plural" | "selectordinal" */
    val type: String,
    val required: List<String>,
    val provided: List<String>,
    val missing: List<String>,
)

enum class ErrorType {
    SYNTAX,
    MISSING_ARG,
    TYPE_MISMATCH,
    INTERNAL,
}

@Serializable
data class FormatError(
    val type: ErrorType,
    val message: String,
    /** 0-based character offset into the template, when known. */
    val offset: Int? = null,
)

@Serializable
data class LocaleInfo(
    val tag: String,
    val displayName: String,
)

@Serializable
data class PrettifyResponse(
    val template: String,
)

@Serializable
data class LocaleResult(
    val tag: String,
    val displayName: String,
    val output: String? = null,
    val error: FormatError? = null,
    val pluralChecks: List<PluralCheck> = emptyList(),
)
