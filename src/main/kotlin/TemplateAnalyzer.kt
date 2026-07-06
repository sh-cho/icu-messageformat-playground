@file:Suppress("DEPRECATION") // MessagePatternUtil is marked ICU-internal but is the cleanest AST.

package com.joebrothers.icuplayground

import com.ibm.icu.text.MessagePattern.ArgType
import com.ibm.icu.text.MessagePatternUtil
import com.ibm.icu.text.MessagePatternUtil.ArgNode
import com.ibm.icu.text.MessagePatternUtil.ComplexArgStyleNode
import com.ibm.icu.text.MessagePatternUtil.MessageNode
import com.ibm.icu.text.PluralRules
import com.ibm.icu.util.ULocale

/**
 * Static analysis of an MF1 template: detects arguments (with inferred types)
 * for scaffolding, and checks CLDR plural-category coverage per locale.
 *
 * MF2 uses a different grammar; analysis is skipped for it (returns empty).
 */
object TemplateAnalyzer {

    data class Result(
        val args: List<ArgInfo>,
        val plurals: List<PluralCheck>,
    )

    private val EMPTY = Result(emptyList(), emptyList())

    // CLDR category display order.
    private val ORDER = listOf("zero", "one", "two", "few", "many", "other")

    fun analyze(template: String, locale: ULocale, engine: Engine): Result {
        if (engine != Engine.MF1) return EMPTY
        val root = try {
            MessagePatternUtil.buildMessageNode(template)
        } catch (_: Exception) {
            return EMPTY // syntax errors are reported by the renderer
        }

        val args = LinkedHashMap<String, ArgInfo>() // dedupe by name, keep first
        val plurals = mutableListOf<PluralCheck>()
        walk(root, locale, args, plurals)
        return Result(args.values.toList(), plurals)
    }

    private fun walk(
        node: MessageNode,
        locale: ULocale,
        args: LinkedHashMap<String, ArgInfo>,
        plurals: MutableList<PluralCheck>,
    ) {
        for (content in node.contents) {
            if (content !is ArgNode) continue
            val name = content.name ?: content.number.toString()
            val type = content.argType

            args.putIfAbsent(name, ArgInfo(name, inferType(content)))

            when (type) {
                ArgType.PLURAL, ArgType.SELECTORDINAL -> {
                    val style = content.complexStyle ?: continue
                    plurals += pluralCheck(name, type, style, locale)
                    recurseVariants(style, locale, args, plurals)
                }
                ArgType.SELECT -> content.complexStyle?.let {
                    recurseVariants(it, locale, args, plurals)
                }
                else -> {}
            }
        }
    }

    private fun recurseVariants(
        style: ComplexArgStyleNode,
        locale: ULocale,
        args: LinkedHashMap<String, ArgInfo>,
        plurals: MutableList<PluralCheck>,
    ) {
        for (variant in style.variants) {
            variant.message?.let { walk(it, locale, args, plurals) }
        }
    }

    private fun inferType(arg: ArgNode): String = when (arg.argType) {
        ArgType.PLURAL, ArgType.SELECTORDINAL -> "number"
        ArgType.SELECT -> "string"
        ArgType.SIMPLE -> when (arg.typeName?.lowercase()) {
            "number", "spellout", "ordinal", "duration" -> "number"
            "date" -> "date"
            "time" -> "time"
            else -> "string"
        }
        else -> "string" // NONE: plain {x}
    }

    private fun pluralCheck(
        name: String,
        type: ArgType,
        style: ComplexArgStyleNode,
        locale: ULocale,
    ): PluralCheck {
        val provided = mutableSetOf<String>()
        val explicit = mutableSetOf<Double>()
        for (variant in style.variants) {
            if (variant.isSelectorNumeric) explicit += variant.selectorValue
            else provided += variant.selector
        }

        val pluralType =
            if (type == ArgType.SELECTORDINAL) PluralRules.PluralType.ORDINAL
            else PluralRules.PluralType.CARDINAL
        val rules = PluralRules.forLocale(locale, pluralType)
        val required = rules.keywords

        val missing = required.filter { kw ->
            if (kw in provided) return@filter false
            // A category is also covered if all its sample values are handled by
            // explicit `=N` selectors (e.g. English `one` when `=1` is present).
            val samples = runCatching { rules.getSamples(kw) }.getOrNull()
            if (!samples.isNullOrEmpty() && samples.all { it in explicit }) return@filter false
            true
        }

        return PluralCheck(
            argName = name,
            type = if (type == ArgType.SELECTORDINAL) "selectordinal" else "plural",
            required = required.sortedBy { ORDER.indexOf(it) },
            provided = provided.sortedBy { ORDER.indexOf(it) },
            missing = missing.sortedBy { ORDER.indexOf(it) },
        )
    }
}
