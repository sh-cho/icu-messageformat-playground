package com.icuplayground

import com.ibm.icu.message2.MessageFormatter
import com.ibm.icu.text.MessageFormat
import com.ibm.icu.util.ULocale
import java.text.ParseException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Renders an ICU MessageFormat template with icu4j (MF1 = text.MessageFormat,
 * MF2 = message2.MessageFormatter). All failures are mapped to a [FormatError];
 * the route returns HTTP 200 with that body, never a 4xx — in a playground an
 * error is a normal result the UI displays.
 */
object Renderer {

    /** Render timeout (§11) — guards against pathologically deep/expensive patterns. */
    private const val TIMEOUT_MS = 2000L

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "icu-render").apply { isDaemon = true }
    }

    /** Production entry point: runs [render] under a timeout on a worker thread. */
    fun renderGuarded(req: FormatRequest): FormatResponse {
        val future = executor.submit<FormatResponse> { render(req) }
        return try {
            future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            FormatResponse(error = FormatError(ErrorType.INTERNAL, "Render timed out after ${TIMEOUT_MS}ms"))
        } catch (e: ExecutionException) {
            FormatResponse(error = FormatError(ErrorType.INTERNAL, e.cause?.message ?: "Render failed"))
        }
    }

    fun render(req: FormatRequest): FormatResponse {
        val uLocale = ULocale.forLanguageTag(req.locale)
        // Static analysis is independent of rendering — attach it to every response.
        val analysis = TemplateAnalyzer.analyze(req.template, uLocale, req.engine)

        val args: Map<String, Any?> = try {
            coerce(req.args)
        } catch (e: CoercionException) {
            return FormatResponse(
                error = FormatError(e.type, e.message ?: "Argument coercion failed"),
                detectedArgs = analysis.args,
                pluralChecks = analysis.plurals,
            )
        }

        val locale = uLocale.toLocale()
        val resp = when (req.engine) {
            Engine.MF1 -> renderMf1(req.template, locale, args)
            Engine.MF2 -> renderMf2(req.template, locale, args)
        }
        return resp.copy(detectedArgs = analysis.args, pluralChecks = analysis.plurals)
    }

    private fun renderMf1(template: String, locale: java.util.Locale, args: Map<String, Any?>): FormatResponse {
        val formatter = try {
            MessageFormat(template, locale)
        } catch (e: IllegalArgumentException) {
            return FormatResponse(error = FormatError(ErrorType.SYNTAX, syntaxMessage(e), extractOffset(e)))
        }

        // MF1 silently renders an unresolved placeholder as literal "{name}" instead of
        // throwing, so detect missing args up front (§6: null/absent => MISSING_ARG).
        val missing = formatter.argumentNames.filter { it !in args.keys || args[it] == null }
        if (missing.isNotEmpty()) {
            return FormatResponse(
                error = FormatError(
                    ErrorType.MISSING_ARG,
                    "Missing argument(s): ${missing.joinToString(", ")}",
                ),
            )
        }

        return try {
            FormatResponse(output = formatter.format(args))
        } catch (e: IllegalArgumentException) {
            FormatResponse(error = classifyRuntime(e))
        } catch (e: Exception) {
            FormatResponse(error = FormatError(ErrorType.INTERNAL, e.message ?: e.toString()))
        }
    }

    private fun renderMf2(template: String, locale: java.util.Locale, args: Map<String, Any?>): FormatResponse {
        val formatter = try {
            MessageFormatter.builder()
                .setPattern(template)
                .setLocale(locale)
                .build()
        } catch (e: Exception) {
            return FormatResponse(error = FormatError(ErrorType.SYNTAX, syntaxMessage(e), extractOffset(e)))
        }

        return try {
            FormatResponse(output = formatter.formatToString(args))
        } catch (e: IllegalArgumentException) {
            FormatResponse(error = classifyRuntime(e))
        } catch (e: Exception) {
            FormatResponse(error = FormatError(ErrorType.INTERNAL, e.message ?: e.toString()))
        }
    }

    /** Distinguish a missing argument from a type mismatch using the exception text. */
    private fun classifyRuntime(e: IllegalArgumentException): FormatError {
        val msg = e.message ?: e.toString()
        val lower = msg.lowercase()
        val type = when {
            "no argument" in lower ||
                "not available" in lower ||
                "missing" in lower ||
                ("argument" in lower && "for" in lower && "available" in lower) -> ErrorType.MISSING_ARG
            else -> ErrorType.TYPE_MISMATCH
        }
        return FormatError(type, msg, extractOffset(e))
    }

    private fun syntaxMessage(e: Throwable): String = e.message ?: e.toString()

    /** Best-effort offset extraction: ParseException, or any cause exposing getOffset()/getErrorOffset(). */
    private fun extractOffset(t: Throwable?): Int? {
        var cur: Throwable? = t
        val seen = HashSet<Throwable>()
        while (cur != null && seen.add(cur)) {
            if (cur is ParseException && cur.errorOffset >= 0) return cur.errorOffset
            offsetViaReflection(cur)?.let { return it }
            cur = cur.cause
        }
        return null
    }

    private fun offsetViaReflection(t: Throwable): Int? {
        for (name in listOf("getOffset", "getErrorOffset")) {
            try {
                val m = t.javaClass.getMethod(name)
                val v = m.invoke(t)
                if (v is Int && v >= 0) return v
            } catch (_: Exception) {
                // method not present on this exception type — try the next
            }
        }
        return null
    }
}
