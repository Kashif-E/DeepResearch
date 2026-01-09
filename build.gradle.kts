plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.2.0"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

application {
    mainClass.set(project.findProperty("mainClass")?.toString() ?: "ai.koog.deepresearch.examples.OllamaExampleKt")
}

repositories {
    mavenCentral()
}

dependencies {

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
// Kotlin serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
// HTTP client for API calls (Tavily search)
    implementation("io.ktor:ktor-client-core:3.2.2")
    implementation("io.ktor:ktor-client-cio:3.2.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.2.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.2.2")
// Logging
    implementation("io.github.oshai:kotlin-logging:7.0.7")
    implementation("ch.qos.logback:logback-classic:1.5.13")
// Koog framework modules (from Maven Central or local)
// Replace with actual published artifact coordinates when available
    implementation("ai.koog:koog-agents:0.6.0")
    implementation("ai.koog:agents-core:0.6.0")
    implementation("ai.koog:agents-tools:0.6.0")
    implementation("ai.koog:agents-mcp:0.6.0")
    implementation("ai.koog:prompt-model:0.6.0")
    implementation("ai.koog:prompt-llm:0.6.0")
    implementation("ai.koog:prompt-executor-model:0.6.0")
    implementation("ai.koog:prompt-executor-llms-all:0.6.0")
    implementation("ai.koog:prompt-structure:0.6.0")

}

sourceSets {
    main {
        kotlin.srcDirs("src/kotlin")
        resources.srcDirs("src/resources")
    }
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}