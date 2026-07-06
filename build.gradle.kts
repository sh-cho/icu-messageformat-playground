import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.2.20"
    id("io.ktor.plugin") version "3.3.0"
    // Native-image build only; doesn't affect the JVM fat-jar path (GraalVM JDK is
    // needed only when nativeCompile actually runs).
    id("org.graalvm.buildtools.native") version "1.1.3"
}

group = "com.joebrothers.icuplayground"

// Version: VERSION env / -Pversion (CI passes the git tag; Docker forwards it as a
// build-arg since .dockerignore strips .git) -> `git describe` -> "1.0.0-dev" fallback.
version = (System.getenv("VERSION")?.takeIf { it.isNotBlank() }?.removePrefix("v") ?: run {
    runCatching {
        providers.exec { commandLine("git", "describe", "--tags", "--always", "--dirty") }
            .standardOutput.asText.get().trim().removePrefix("v")
    }.getOrNull()?.takeIf { it.isNotBlank() }
} ?: "1.0.0-dev")

application {
    mainClass.set("com.joebrothers.icuplayground.ApplicationKt")
}

repositories {
    mavenCentral()
}

// Lock resolved versions to gradle.lockfile so builds fail on dependency drift, and the
// recorded transitives let Trivy `fs` see Java CVEs. Scoped to real classpaths — locking
// all configs would pin churny Kotlin/plugin internals. Regenerate with --write-locks.
val lockedConfigurations = setOf(
    "compileClasspath", "runtimeClasspath",
    "testCompileClasspath", "testRuntimeClasspath",
    "nativeImageClasspath", "nativeImageTestClasspath",
)
configurations.matching { it.name in lockedConfigurations }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-cio")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-cors")
    implementation("io.ktor:ktor-server-status-pages")

    implementation("com.ibm.icu:icu4j:78.1")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host")
}

// Build on JDK 25 (mise) but emit JVM 21 bytecode: runs on stock JRE 21+, and 21 is
// Kotlin's max supported target.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

tasks.test {
    useJUnitPlatform()
}

// GraalVM native-image (`./gradlew nativeCompile`, or via Dockerfile.native). icu4j CLDR
// data + the bundled frontend are classpath resources (see the committed resource-config
// under src/main/resources/META-INF/native-image); reflection metadata is traced at build.
graalvmNative {
    binaries {
        named("main") {
            imageName.set("playground")
            mainClass.set("com.joebrothers.icuplayground.ApplicationKt")
            buildArgs.add("--no-fallback")
            buildArgs.add("-H:+ReportExceptionStackTraces")
            // Link everything but glibc statically (zlib, libstdc++, …) so the binary runs
            // on a minimal distroless/base image. Full --static would need a musl toolchain.
            buildArgs.add("-H:+StaticExecutableWithDynamicLibC")
        }
    }
    // Pull community reachability metadata (logback, etc.) automatically.
    metadataRepository {
        enabled.set(true)
    }
}

// Frontend: `pnpm build` writes its dist into src/main/resources/static, which Ktor
// serves and the fat jar bundles.
val frontendDir = layout.projectDirectory.dir("frontend")
val staticDir = layout.projectDirectory.dir("src/main/resources/static")
val isWindows = System.getProperty("os.name").lowercase().contains("win")
val pnpm = if (isWindows) "pnpm.cmd" else "pnpm"

val installFrontend by tasks.registering(Exec::class) {
    workingDir = frontendDir.asFile
    inputs.file(frontendDir.file("package.json"))
    inputs.file(frontendDir.file("pnpm-lock.yaml")).optional(true)
    outputs.dir(frontendDir.dir("node_modules"))
    // Honor the committed pnpm-lock.yaml; fail (don't silently mutate it) on drift.
    commandLine(pnpm, "install", "--frozen-lockfile")
}

val buildFrontend by tasks.registering(Exec::class) {
    dependsOn(installFrontend)
    workingDir = frontendDir.asFile
    inputs.dir(frontendDir.dir("src"))
    inputs.file(frontendDir.file("package.json"))
    inputs.file(frontendDir.file("vite.config.ts"))
    inputs.file(frontendDir.file("index.html"))
    outputs.dir(staticDir)
    commandLine(pnpm, "run", "build")
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildFrontend)
}

tasks.named("clean") {
    doLast {
        staticDir.asFile.deleteRecursively()
    }
}
