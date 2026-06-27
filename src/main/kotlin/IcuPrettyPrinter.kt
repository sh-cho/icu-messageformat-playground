@file:Suppress("DEPRECATION") // MessagePatternUtil is ICU-internal but the cleanest AST.

package com.icuplayground

import com.ibm.icu.text.MessagePattern.ArgType
import com.ibm.icu.text.MessagePatternUtil
import com.ibm.icu.text.MessagePatternUtil.ArgNode
import com.ibm.icu.text.MessagePatternUtil.MessageContentsNode
import com.ibm.icu.text.MessagePatternUtil.MessageNode
import com.ibm.icu.text.MessagePatternUtil.TextNode

/**
 * Pretty-prints an MF1 ICU MessageFormat template by putting each plural/select
 * variant on its own indented line.
 *
 * Whitespace *inside* a message is significant (it's literal output), so we only
 * add whitespace at structural positions (between variants, before the closing
 * brace) — never inside message text. As a safety net the result is re-parsed and
 * its canonical (minified) form compared to the original's; if they differ for any
 * reason, the original is returned unchanged. So formatting can never alter output.
 */
object IcuPrettyPrinter {

    private const val INDENT = "  "

    fun prettify(template: String, engine: Engine): String {
        if (engine != Engine.MF1) return template
        val ast = runCatching { MessagePatternUtil.buildMessageNode(template) }.getOrNull()
            ?: return template

        val pretty = buildString { emitMessage(ast, 0, multiline = true) }

        // Safety: re-parse and confirm the structure is identical.
        val reparsed = runCatching { MessagePatternUtil.buildMessageNode(pretty) }.getOrNull()
            ?: return template
        if (canonical(ast) != canonical(reparsed)) return template
        return pretty
    }

    /** Minified emission used only for the equivalence check. */
    private fun canonical(node: MessageNode): String =
        buildString { emitMessage(node, 0, multiline = false) }

    private fun StringBuilder.emitMessage(node: MessageNode, depth: Int, multiline: Boolean) {
        for (content in node.contents) {
            when (content.type) {
                MessageContentsNode.Type.TEXT -> append(escapeText((content as TextNode).text))
                MessageContentsNode.Type.REPLACE_NUMBER -> append('#')
                MessageContentsNode.Type.ARG -> emitArg(content as ArgNode, depth, multiline)
                else -> {}
            }
        }
    }

    private fun StringBuilder.emitArg(arg: ArgNode, depth: Int, multiline: Boolean) {
        val name = arg.name ?: arg.number.toString()
        when (arg.argType) {
            ArgType.NONE -> append('{').append(name).append('}')

            ArgType.SIMPLE -> {
                append('{').append(name).append(", ").append(arg.typeName)
                arg.simpleStyle?.let { append(", ").append(it) }
                append('}')
            }

            ArgType.PLURAL, ArgType.SELECT, ArgType.SELECTORDINAL -> {
                val style = arg.complexStyle
                if (style == null) {
                    append('{').append(name).append('}')
                    return
                }
                val keyword = when (arg.argType) {
                    ArgType.PLURAL -> "plural"
                    ArgType.SELECTORDINAL -> "selectordinal"
                    else -> "select"
                }
                append('{').append(name).append(", ").append(keyword).append(',')
                if (style.hasExplicitOffset()) append(" offset:").append(numStr(style.offset))

                val childIndent = INDENT.repeat(depth + 1)
                for (variant in style.variants) {
                    if (multiline) append('\n').append(childIndent) else append(' ')
                    val selector =
                        if (variant.isSelectorNumeric) "=" + numStr(variant.selectorValue)
                        else variant.selector
                    append(selector).append(" {")
                    variant.message?.let { emitMessage(it, depth + 1, multiline) }
                    append('}')
                }
                if (multiline) append('\n').append(INDENT.repeat(depth))
                append('}')
            }

            else -> append('{').append(name).append('}') // CHOICE (deprecated) — leave minimal
        }
    }

    /** Re-escape literal text: apostrophes and curly braces are ICU syntax. */
    private fun escapeText(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                '\'' -> append("''")
                '{', '}' -> append('\'').append(ch).append('\'')
                else -> append(ch)
            }
        }
    }

    private fun numStr(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}
