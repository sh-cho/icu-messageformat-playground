import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("io.ktor.plugin") version "3.3.0"
}

group = "com.icuplayground"
version = "1.0.0"

application {
    mainClass.set("com.icuplayground.ApplicationKt")
}

repositories {
    mavenCentral()
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

// Run the build on JDK 25 (mise) but emit JVM 21 bytecode so the artifact runs
// on a stock JRE 21+ (and to satisfy Kotlin's max supported JVM target).
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

// ---------------------------------------------------------------------------
// Frontend build: `pnpm install && pnpm build` writes its dist into
// src/main/resources/static, which Ktor serves and the fat jar bundles.
// ---------------------------------------------------------------------------
val frontendDir = layout.projectDirectory.dir("frontend")
val staticDir = layout.projectDirectory.dir("src/main/resources/static")
val isWindows = System.getProperty("os.name").lowercase().contains("win")
val pnpm = if (isWindows) "pnpm.cmd" else "pnpm"

val installFrontend by tasks.registering(Exec::class) {
    workingDir = frontendDir.asFile
    inputs.file(frontendDir.file("package.json"))
    inputs.file(frontendDir.file("pnpm-lock.yaml")).optional(true)
    outputs.dir(frontendDir.dir("node_modules"))
    commandLine(pnpm, "install", "--frozen-lockfile=false")
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
