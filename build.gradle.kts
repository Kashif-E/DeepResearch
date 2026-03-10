plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
    id("org.graalvm.buildtools.native") version "0.10.6"
}

group = "org.example"
version = "1.0-SNAPSHOT"

application {
    mainClass.set(project.findProperty("mainClass")?.toString() ?: "ai.kash.delve.cli.DelveCli")
    applicationName = "Delve"
}

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Kotlin serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // HTTP client for API calls (Tavily search) — OkHttp via transitive dep, GraalVM-friendly
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    // Logging
    implementation("io.github.oshai:kotlin-logging:7.0.7")
    implementation("ch.qos.logback:logback-classic:1.5.13")
    // Koog framework modules
    implementation("ai.koog:koog-agents:0.6.4")
    implementation("ai.koog:agents-core:0.6.4")
    implementation("ai.koog:agents-tools:0.6.4")
    implementation("ai.koog:agents-mcp:0.6.4")
    implementation("ai.koog:prompt-model:0.6.4")
    implementation("ai.koog:prompt-llm:0.6.4")
    implementation("ai.koog:prompt-executor-model:0.6.4")
    implementation("ai.koog:prompt-executor-llms-all:0.6.4")
    implementation("ai.koog:prompt-structure:0.6.4")
    // Koog RAG modules
    implementation("ai.koog:rag-base:0.6.4")
    implementation("ai.koog:vector-storage:0.6.4")
    implementation("ai.koog:embeddings-base:0.6.4")
    implementation("ai.koog:embeddings-llm:0.6.4")
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
    jvmToolchain(25)
}

graalvmNative {
    toolchainDetection.set(false)

    binaries {
        named("main") {
            imageName.set("delve")
            mainClass.set("ai.kash.delve.cli.DelveCli")

            buildArgs.addAll(
                "--no-fallback",
                "--enable-url-protocols=http,https",
                "-H:+ReportExceptionStackTraces",
                "--initialize-at-build-time=kotlin,org.slf4j,io.github.oshai.kotlinlogging,ch.qos.logback,org.xml.sax,javax.xml,com.sun.org.apache.xerces,jdk.xml.internal",
                "--initialize-at-run-time=io.netty,kotlin.uuid.SecureRandomHolder,kotlinx.coroutines,kotlinx.atomicfu",
                "-H:+AddAllCharsets",
            )

            // Use JAVA_HOME GraalVM directly — no toolchain resolution needed
        }
    }

    metadataRepository {
        enabled.set(true)
    }

    agent {
        defaultMode.set("standard")
        metadataCopy {
            mergeWithExisting.set(true)
            inputTaskNames.add("run")
            outputDirectories.add("src/resources/META-INF/native-image")
        }
    }
}
