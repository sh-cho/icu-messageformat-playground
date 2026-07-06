package com.joebrothers.icuplayground

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.util.LocaleData
import com.ibm.icu.util.VersionInfo
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

const val REPO_URL = "https://github.com/sh-cho/icu-messageformat-playground"

// Runtime facts don't change over a process's life, so compute once and reuse.
private val META: MetaResponse by lazy {
    MetaResponse(
        icu4jVersion = VersionInfo.ICU_VERSION.toString(),
        unicodeVersion = UCharacter.getUnicodeVersion().toString(),
        cldrVersion = LocaleData.getCLDRVersion().toString(),
        engines = listOf(
            EngineInfo("mf1", "ICU MessageFormat"),
            EngineInfo("mf2", "MessageFormat 2.0", preview = true),
        ),
        localeCount = Locales.list.size,
        javaVersion = System.getProperty("java.version") ?: "unknown",
        javaVm = System.getProperty("java.vm.name") ?: "unknown",
        kotlinVersion = KotlinVersion.CURRENT.toString(),
        // GraalVM sets this property only inside a native image at run time.
        runtime = if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) "native-image" else "JVM",
        repoUrl = REPO_URL,
    )
}

fun Route.metaRoutes() {
    get("/api/meta") {
        call.respond(META)
    }
}
