@file:JvmName("DelveCli")

package ai.kash.delve.cli

import ai.kash.delve.KoogDeepResearch
import ai.kash.delve.config.DeepResearchConfig
import ai.kash.delve.graph.DeepResearchEvent
import ai.kash.delve.memory.*
import ai.kash.delve.rag.DocumentRAG
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val R = "\u001B[0m"
private const val B = "\u001B[1m"
private const val D = "\u001B[2m"
private const val I = "\u001B[3m"

private const val RED = "\u001B[31m"
private const val GRN = "\u001B[32m"
private const val YLW = "\u001B[33m"
private const val BLU = "\u001B[34m"
private const val MAG = "\u001B[35m"
private const val CYN = "\u001B[36m"
private const val WHT = "\u001B[37m"

private const val BGRN = "\u001B[92m"
private const val BBLU = "\u001B[94m"
private const val BMAG = "\u001B[95m"
private const val BCYN = "\u001B[96m"
private const val BWHT = "\u001B[97m"

private const val MINT   = "\u001B[38;5;48m"
private const val JADE   = "\u001B[38;5;43m"
private const val PINE   = "\u001B[38;5;37m"
private const val CHALK  = "\u001B[38;5;252m"
private const val DIM    = "\u001B[38;5;242m"

data class FileContext(
    val query: String,
    val files: List<AttachedFile> = emptyList()
)

data class AttachedFile(
    val path: File,
    val content: String
)

private fun parseFileReferences(input: String): FileContext {
    val tokens = mutableListOf<String>()
    val refs = mutableListOf<String>()

    val regex = Regex("""@((?:"[^"]*"|'[^']*'|[^\s])+)|\S+""")
    for (match in regex.findAll(input)) {
        val refGroup = match.groups[1]
        if (refGroup != null) {
            refs.add(refGroup.value.removeSurrounding("\"").removeSurrounding("'"))
        } else {
            tokens.add(match.value)
        }
    }

    if (refs.isEmpty()) return FileContext(query = input)

    val files = mutableListOf<AttachedFile>()
    for (ref in refs) {
        val expanded = ref.replace("~", System.getProperty("user.home"))
        var target = File(expanded)

        if (!target.exists() && !target.name.contains("*")) {
            val parent = target.parentFile ?: File(".")
            val match = parent.listFiles()
                ?.firstOrNull { it.name.equals(target.name, ignoreCase = true) }
            if (match != null) target = match
        }

        if (target.name.contains("*")) {
            val parent = target.parentFile ?: File(".")
            val pattern = target.name
            if (parent.isDirectory) {
                parent.listFiles()
                    ?.filter { it.isFile && it.name.matches(
                        globToRegex(
                            pattern
                        )
                    ) }
                    ?.sortedBy { it.name }
                    ?.forEach { f -> readAttachedFile(f)?.let { files.add(it) } }
            }
        } else if (target.isDirectory) {
            target.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") }
                ?.sortedBy { it.name }
                ?.take(20)
                ?.forEach { f -> readAttachedFile(f)?.let { files.add(it) } }
        } else {
            val attached = readAttachedFile(target)
            if (attached != null) {
                files.add(attached)
            } else {
                println("  \u001B[38;5;208m\u26A0\u001B[0m File not found: $ref")
            }
        }
    }

    return FileContext(query = tokens.joinToString(" "), files = files)
}

private val HOME_DIR = File(System.getProperty("user.home"))

private fun readAttachedFile(file: File): AttachedFile? {
    if (!file.exists() || !file.isFile) return null
    if (file.length() > 500_000) return null
    val canonical = file.canonicalFile
    val cwd = File(".").canonicalFile
    if (!canonical.startsWith(HOME_DIR) && !canonical.startsWith(cwd)) {
        System.err.println("  ${YLW}\u26A0${R} Blocked: ${file.path} is outside allowed directories")
        return null
    }
    return try {
        AttachedFile(path = file, content = file.readText())
    } catch (_: Exception) { null }
}

private fun globToRegex(pattern: String): Regex {
    val escaped = pattern
        .replace(".", "\\.")
        .replace("*", ".*")
        .replace("?", ".")
    return Regex("^$escaped$")
}

private fun buildAugmentedQuery(query: String, files: List<AttachedFile>): String {
    if (files.isEmpty()) return query
    val context = buildString {
        appendLine("The user has attached the following files as context for this research query.")
        appendLine("Use them to inform your research — they may contain relevant data, code, or background information.")
        appendLine()
        for (file in files) {
            appendLine("--- FILE: ${file.path.name} ---")
            appendLine(file.content.take(100_000))
            appendLine("--- END FILE ---")
            appendLine()
        }
        appendLine("RESEARCH QUERY:")
    }
    return context + query
}

@Serializable
data class OllamaModelList(val models: List<OllamaModelInfo> = emptyList())

@Serializable
data class OllamaModelDetails(
    val family: String = "",
    val parameter_size: String = "",
    val quantization_level: String = ""
)

@Serializable
data class OllamaModelInfo(
    val name: String,
    val size: Long = 0,
    val modified_at: String = "",
    val digest: String = "",
    val details: OllamaModelDetails = OllamaModelDetails(),
    val remote_model: String? = null
) {
    val isCloud: Boolean get() = remote_model != null && remote_model.isNotBlank()
    val isEmbeddingOnly: Boolean get() = details.family.contains("bert", ignoreCase = true)
        || details.family.contains("embed", ignoreCase = true)
        || name.contains("embed", ignoreCase = true)
    val isUsableLLM: Boolean get() = !isEmbeddingOnly
    val sizeLabel: String get() = when {
        isCloud -> "cloud"
        size > 100_000_000 -> String.format("%.1fGB", size / 1_000_000_000.0)
        size > 0 -> String.format("%.0fMB", size / 1_000_000.0)
        else -> ""
    }
}

private fun httpGet(url: String, timeoutMs: Int = 5_000): String {
    val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = timeoutMs
    conn.readTimeout = timeoutMs
    conn.requestMethod = "GET"
    return try {
        conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

private val configJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class DelveMCPServer(
    val transport: String = "stdio",
    val command: String? = null,
    val args: List<String> = emptyList(),
    val url: String? = null,
    val env: Map<String, String> = emptyMap(),
    val tools: List<String>? = null
)

@Serializable
data class DelveConfig(
    val tavilyApiKey: String? = null,
    val defaultModel: String? = null,
    val maxConcurrentResearch: Int = 3,
    val maxSupervisorIterations: Int = 10,
    val maxToolCalls: Int = 20,
    val temperature: Double = 0.0,
    val enableClarification: Boolean = true,
    val mcpServers: Map<String, DelveMCPServer> = emptyMap()
)

private val CONFIG_DIR = File(System.getProperty("user.home"), ".config/delve")
private val CONFIG_FILE = File(CONFIG_DIR, "config.json")

private fun loadConfig(): DelveConfig {
    if (!CONFIG_FILE.exists()) return DelveConfig()
    return try {
        configJson.decodeFromString<DelveConfig>(
            CONFIG_FILE.readText())
    } catch (e: Exception) {
        System.err.println("  Warning: Failed to parse ${CONFIG_FILE.path}: ${e.message}")
        System.err.println("  Using default configuration. Fix or delete the config file to resolve.")
        DelveConfig()
    }
}

private fun saveConfig(config: DelveConfig) {
    CONFIG_DIR.mkdirs()
    CONFIG_FILE.writeText(configJson.encodeToString(config))
    CONFIG_FILE.setReadable(false, false)
    CONFIG_FILE.setReadable(true, true)
    CONFIG_FILE.setWritable(false, false)
    CONFIG_FILE.setWritable(true, true)
}

private fun DelveMCPServer.toMCPConfig(): ai.kash.delve.config.MCPConfig {
    return when (transport.lowercase()) {
        "sse" -> ai.kash.delve.config.MCPConfig(
            url = url,
            transport = _root_ide_package_.ai.kash.delve.config.MCPTransport.SSE,
            tools = tools
        )
        else -> ai.kash.delve.config.MCPConfig(
            command = buildList {
                command?.let { add(it) }
                addAll(args)
            },
            environment = env,
            transport = _root_ide_package_.ai.kash.delve.config.MCPTransport.STDIO,
            tools = tools
        )
    }
}

private fun DelveConfig.mcpConfigs(): Map<String, ai.kash.delve.config.MCPConfig> {
    return mcpServers.mapValues { (_, server) -> server.toMCPConfig() }
}

private val SPINNER_FRAMES = arrayOf(
    "${PINE}\u280B${R}", "${PINE}\u2819${R}", "${PINE}\u2839${R}", "${PINE}\u2838${R}",
    "${PINE}\u283C${R}", "${PINE}\u2834${R}", "${JADE}\u2826${R}", "${JADE}\u2827${R}",
    "${JADE}\u2807${R}", "${PINE}\u280F${R}"
)

private fun CoroutineScope.launchSpinner(label: String): Job {
    return launch(Dispatchers.IO) {
        var frame = 0
        val startMs = System.currentTimeMillis()
        try {
            while (isActive) {
                val secs = (System.currentTimeMillis() - startMs) / 1000
                val timeStr = if (secs >= 60) "${secs / 60}m ${secs % 60}s" else "${secs}s"
                val spinner = SPINNER_FRAMES[frame % SPINNER_FRAMES.size]
                print("\r    $spinner ${D}$label $timeStr${R}    ")
                System.out.flush()
                delay(100)
                frame++
            }
        } finally {
            print("\r${" ".repeat(72)}\r")
            System.out.flush()
        }
    }
}

private val REPORTS_DIR = File(System.getProperty("user.home"), "delve-reports")

private fun saveReport(query: String, report: String, elapsed: Long, sourceCount: Int): File {
    REPORTS_DIR.mkdirs()
    val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    val slug = query.take(40).replace(Regex("[^a-zA-Z0-9]+"), "-").trim('-').lowercase()
    val file = File(REPORTS_DIR, "${ts}_${slug}.md")
    file.writeText(buildString {
        appendLine("# ${query.lines().first().trim()}")
        appendLine()
        appendLine("> Generated by Delve | ${elapsed}s | $sourceCount sources")
        appendLine()
        appendLine(report)
    })
    return file
}

private fun copyToClipboard(text: String): Boolean {
    val osName = System.getProperty("os.name").lowercase()
    val command = when {
        osName.contains("mac") -> listOf("pbcopy")
        osName.contains("linux") -> listOf("xclip", "-selection", "clipboard")
        osName.contains("win") -> listOf("clip.exe")
        else -> return false
    }
    return try {
        val pb = ProcessBuilder(command).start()
        pb.outputStream.use { it.write(text.toByteArray()); it.flush() }
        pb.waitFor() == 0
    } catch (_: Exception) { false }
}

private fun setupFileLogging() {
    try {
        val logDir = File(System.getProperty("user.home"), ".config/delve")
        logDir.mkdirs()
        val logFile = File(logDir, "delve.log")

        val context = org.slf4j.LoggerFactory.getILoggerFactory() as ch.qos.logback.classic.LoggerContext
        val fileAppender = ch.qos.logback.core.FileAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
            this.context = context
            name = "FILE"
            file = logFile.absolutePath
            isAppend = true
            encoder = ch.qos.logback.classic.encoder.PatternLayoutEncoder().apply {
                this.context = context
                pattern = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
                start()
            }
            start()
        }

        val rootLogger = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
        rootLogger.addAppender(fileAppender)

        context.getLogger("ai.kash.delve").level = ch.qos.logback.classic.Level.DEBUG
        context.getLogger("ai.koog").level = ch.qos.logback.classic.Level.DEBUG
    } catch (e: Exception) {
        System.err.println("Warning: Failed to setup file logging: ${e.message}")
    }
}

fun main(args: Array<String>) = runBlocking {
    setupFileLogging()
    printBanner()

    var userConfig = loadConfig()

    if (args.contains("--set-key")) {
        val keyIdx = args.indexOf("--set-key")
        val newKey = args.getOrNull(keyIdx + 1)
        if (newKey.isNullOrBlank() || newKey.startsWith("--")) {
            println("  ${RED}\u2717${R} Usage: delve --set-key <tavily-api-key>")
        } else {
            userConfig = userConfig.copy(tavilyApiKey = newKey)
            saveConfig(userConfig)
            val masked = newKey.take(4) + "*".repeat(maxOf(0, newKey.length - 4))
            println("  ${JADE}\u2713${R} Tavily key saved ${D}($masked)${R}")
        }
        return@runBlocking
    }

    val ollamaRunning = checkOllama()
    if (!ollamaRunning) {
        printOllamaSetupGuide()
        return@runBlocking
    }

    val models = listOllamaModels()
    if (models.isEmpty()) {
        printNoModelsGuide()
        return@runBlocking
    }

    if (args.contains("--set-model")) {
        val modelIdx = args.indexOf("--set-model")
        val modelName = args.getOrNull(modelIdx + 1)
        if (modelName != null && models.any { it.name == modelName }) {
            userConfig = userConfig.copy(defaultModel = modelName)
            saveConfig(userConfig)
            println("  ${JADE}\u2713${R} Default model set to ${B}$modelName${R}")
        } else if (modelName != null) {
            println("  ${RED}\u2717${R} Model '$modelName' not found locally.")
            println("  ${D}Available models:${R}")
            models.forEach { println("    ${CHALK}${it.name}${R}") }
        } else {
            println("  ${RED}\u2717${R} Usage: delve --set-model <model-name>")
        }
        return@runBlocking
    }

    val selectedModel = if (args.contains("--model")) {
        val modelIdx = args.indexOf("--model")
        val modelName = args.getOrNull(modelIdx + 1)
        if (modelName != null && models.any { it.name == modelName }) {
            models.first { it.name == modelName }
        } else {
            println("  ${RED}Model '$modelName' not found locally.${R}")
            selectModel(models)
        }
    } else if (userConfig.defaultModel != null && models.any { it.name == userConfig.defaultModel }) {
        val m = models.first { it.name == userConfig.defaultModel }
        println("  ${JADE}\u2713${R} Using default model ${B}${m.name}${R} ${D}(change with --set-model <name>)${R}")
        m
    } else {
        selectModel(models)
    }

    if (userConfig.mcpServers.isNotEmpty()) {
        println("  ${JADE}\u2713${R} MCP servers: ${userConfig.mcpServers.keys.joinToString(", ") { "${B}$it${R}" }}")
    }

    var tavilyKey = System.getenv("TAVILY_API_KEY")?.takeIf { it.isNotBlank() }
        ?: userConfig.tavilyApiKey?.takeIf { it.isNotBlank() }

    if (tavilyKey != null && userConfig.tavilyApiKey != null) {
        val masked = tavilyKey.take(4) + "*".repeat(maxOf(0, tavilyKey.length - 4))
        println("  ${JADE}\u2713${R} Tavily configured ${D}($masked)${R}")
    } else if (tavilyKey != null) {
        println("  ${JADE}\u2713${R} Tavily configured")
    } else {
        println()
        println("  ${YLW}${B}Tavily API key required${R} ${D}for web search${R}")
        println("  ${D}Get a free key at: ${B}https://tavily.com${R}")
        println()
        print("  ${B}Enter your TAVILY_API_KEY:${R} ")
        val input = readMaskedInput()
        println()
        if (input.isNullOrBlank()) {
            println()
            println("  ${RED}\u2717${R} Delve requires a Tavily API key to search the web.")
            println("  ${D}Set it permanently:${R}")
            println("    ${B}export TAVILY_API_KEY=tvly-your-key${R}  ${D}# add to ~/.zshrc${R}")
            println()
            return@runBlocking
        }
        tavilyKey = input
        userConfig = userConfig.copy(tavilyApiKey = input, defaultModel = selectedModel.name)
        saveConfig(userConfig)
        val masked = input.take(4) + "*".repeat(maxOf(0, input.length - 4))
        println("  ${JADE}\u2713${R} Tavily key saved to config ${D}($masked)${R}")
    }

    if (userConfig.defaultModel != selectedModel.name) {
        userConfig = userConfig.copy(defaultModel = selectedModel.name)
        saveConfig(userConfig)
    }

    val rawInput = if (args.isNotEmpty() && !args[0].startsWith("--")) {
        args.filter { !it.startsWith("--") && it != args.getOrNull(args.indexOf("--model") + 1) }
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
    } else null

    val userInput = rawInput ?: promptForQuery() ?: return@runBlocking
    val fileContext = parseFileReferences(userInput)
    val researchQuery = if (fileContext.files.isNotEmpty()) {
        println()
        for (f in fileContext.files) {
            val sizeKb = f.content.length / 1024
            println("  ${JADE}\u25AA${R} ${DIM}attached${R} ${CHALK}${f.path.name}${R} ${DIM}(${sizeKb}KB)${R}")
        }
        buildAugmentedQuery(fileContext.query, fileContext.files)
    } else {
        fileContext.query
    }
    val displayQuery = fileContext.query

    val depth = selectDepth()

    val contextLength = queryModelContextLength(selectedModel.name)
    val llmModel = LLModel(
        provider = LLMProvider.Ollama,
        id = selectedModel.name,
        capabilities = listOf(
            LLMCapability.Temperature,
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.Tools
        ),
        contextLength = contextLength
    )

    val mcpServerConfigs = userConfig.mcpConfigs()

    val config = DeepResearchConfig(
        researchModel = llmModel,
        supervisorModel = llmModel,
        clarifyModel = llmModel,
        finalReportModel = llmModel,
        compressionModel = llmModel,
        summarizationModel = llmModel,
        tavilyApiKey = tavilyKey,
        maxReactToolCalls = depth.toolCalls.coerceAtMost(userConfig.maxToolCalls),
        maxConcurrentResearchUnits = depth.concurrency.coerceAtMost(userConfig.maxConcurrentResearch),
        maxSupervisorIterations = depth.iterations.coerceAtMost(userConfig.maxSupervisorIterations),
        enableClarification = userConfig.enableClarification,
        temperature = userConfig.temperature,
        mcpConfigs = mcpServerConfigs,
    )

    val executor = simpleOllamaAIExecutor()

    println()
    printSessionHeader(displayQuery, selectedModel.name, depth.label, mcpServerConfigs.keys, fileContext.files.map { it.path.name })

    val partialReportRef = java.util.concurrent.atomic.AtomicReference("")
    val completedNormallyRef = java.util.concurrent.atomic.AtomicBoolean(false)
    val shutdownConversationHistory = java.util.concurrent.atomic.AtomicReference<List<ConversationTurn>>(emptyList())

    var rag: DocumentRAG? = null
    if (fileContext.files.isNotEmpty()) {
        println("  ${DIM}Indexing ${fileContext.files.size} file(s) for semantic search...${R}")
        rag = DocumentRAG()
        val chunks = rag.indexFiles(fileContext.files.map { it.path }) { status ->
            println("    ${DIM}$status${R}")
        }
        println("    ${JADE}\u2713${R} ${DIM}$chunks chunks indexed${R}")
        println()
    }

    val researchMemory = ResearchMemory()
    var memoryContext = ""
    if (config.memoryEnabled) {
        try {
            val factCount = researchMemory.initialize { status ->
                println("  ${DIM}$status${R}")
            }
            if (factCount > 0) {
                println("  ${JADE}\u2713${R} ${DIM}$factCount facts from past research loaded${R}")
                memoryContext = researchMemory.retrieveRelevantFacts(researchQuery)
                if (memoryContext.isNotBlank()) {
                    println("  ${JADE}\u2713${R} ${DIM}Found relevant past findings${R}")
                }
            }
        } catch (e: Exception) {
            println("  ${DIM}Memory initialization skipped: ${e.message}${R}")
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        if (!completedNormallyRef.get()) {
            if (partialReportRef.get().isNotBlank()) {
                println()
                println()
                println("  ${YLW}${B}Interrupted${R} ${D}\u2014 saving partial results...${R}")
                val file = saveReport(displayQuery, partialReportRef.get(), 0, 0)
                println("  ${D}Partial report saved: ${file.absolutePath}${R}")
            }
            val turns = shutdownConversationHistory.get()
            val sessionId = researchMemory.currentSessionId
            if (config.memoryEnabled && turns.isNotEmpty() && sessionId != null) {
                runBlocking { researchMemory.saveConversationTurns(sessionId, turns) }
            }
        }
        restoreTerminal()
        print("\u001B[?25h")
    })

    var activeQuery = researchQuery
    var activeConfig = config

    var completedState: ai.kash.delve.state.AgentState? = null
    var lastReport = ""

    try {
        for (attempt in 1..3) {
            val isRetry = attempt > 1
            val agent = KoogDeepResearch.create(activeConfig, executor, rag, memoryContext)
            var researchCount = 0
            val startTime = System.currentTimeMillis()
            var clarificationQuestion: String? = null
            var spinnerJob: Job? = null

            fun stopSpinner() {
                spinnerJob?.cancel()
                spinnerJob = null
            }

            fun elapsed(): String {
                val secs = (System.currentTimeMillis() - startTime) / 1000
                val m = secs / 60; val s = secs % 60
                return if (m > 0) "${m}m ${s}s" else "${s}s"
            }

            var sourceCount = 0

            agent.researchStream(activeQuery)
                .onEach { event ->
                    when (event) {
                        is DeepResearchEvent.SourceSearch, is DeepResearchEvent.SourceFound -> {}
                        else -> stopSpinner()
                    }

                    when (event) {
                        is DeepResearchEvent.Started -> {
                            if (!isRetry) {
                                println()
                                println("  ${PINE}\u25CF${R} ${B}${CHALK}Starting research${R} ${DIM}${elapsed()}${R}")
                            }
                            spinnerJob = launchSpinner("Warming up...")
                        }
                        is DeepResearchEvent.Phase -> {
                            if (isRetry && event.name == "clarify_with_user") {
                                spinnerJob = launchSpinner("Resuming...")
                            } else {
                                val (label, spinLabel) = when (event.name) {
                                    "clarify_with_user" -> "Analyzing query" to "Thinking..."
                                    "write_research_brief" -> "Drafting research plan" to "Generating questions..."
                                    "research_supervisor" -> "Searching sources" to "Searching and analyzing..."
                                    "final_report_generation" -> "Writing report" to "Synthesizing findings..."
                                    else -> event.description to "Working..."
                                }
                                println("  ${PINE}\u25CF${R} ${B}${CHALK}$label${R} ${DIM}${elapsed()}${R}")
                                spinnerJob = launchSpinner(spinLabel)
                            }
                        }
                        is DeepResearchEvent.Clarification -> {
                            clarificationQuestion = event.question
                        }
                        is DeepResearchEvent.ResearchBrief -> {
                            stopSpinner()
                            val briefPreview = event.brief.lines()
                                .map { it.trim() }
                                .firstOrNull { it.length > 15 && !it.startsWith("#") }
                                ?.take(70)
                                ?.let { if (it.length == 70) "${it.trimEnd()}..." else it }
                                ?: "Research brief ready"
                            println("    ${JADE}\u2713${R} ${DIM}$briefPreview${R}")

                            val angles = event.brief.lines()
                                .map { it.trim() }
                                .filter { it.startsWith("-") || it.matches(Regex("^\\d+[.)].*")) }
                                .map { it.removePrefix("-").replaceFirst(Regex("^\\d+[.)]\\s*"), "").trim() }
                                .filter { it.length > 5 }
                            if (angles.isNotEmpty()) {
                                for (angle in angles.take(5)) {
                                    val truncAngle = angle.take(60).let {
                                        if (it.length == 60) "${it.trimEnd()}..." else it
                                    }
                                    println("      ${DIM}\u2022 $truncAngle${R}")
                                }
                            }
                            spinnerJob = launchSpinner("Dispatching researchers...")
                        }
                        is DeepResearchEvent.SourceSearch -> {
                            stopSpinner()
                            val truncQuery = event.query.take(60).let {
                                if (it.length == 60) "${it.trimEnd()}..." else it
                            }
                            println("    ${DIM}\u2315 $truncQuery${R}")
                            spinnerJob = launchSpinner("Waiting for results...")
                        }
                        is DeepResearchEvent.SourceFound -> {
                            sourceCount++
                            stopSpinner()
                            val domain = event.url
                                .removePrefix("https://").removePrefix("http://")
                                .substringBefore("/")
                            val truncTitle = event.title.take(50).let {
                                if (it.length == 50) "${it.trimEnd()}..." else it
                            }
                            println("      ${JADE}\u2192${R} ${DIM}$domain${R} \u2014 $truncTitle")
                            spinnerJob = launchSpinner("Reading sources...")
                        }
                        is DeepResearchEvent.ResearchUpdate -> {
                            researchCount++
                            stopSpinner()
                            println("    ${JADE}\u2713${R} ${DIM}Task $researchCount complete${R}  ${DIM}${elapsed()}${R}")

                            val summaryLine = event.summary.replace("\n", " ").take(80)
                            if (summaryLine.isNotBlank()) {
                                partialReportRef.updateAndGet { it + "\n## Finding $researchCount\n$summaryLine\n" }
                            }
                            spinnerJob = launchSpinner("Searching and analyzing...")
                        }
                        is DeepResearchEvent.Completed -> {
                            val secs = (System.currentTimeMillis() - startTime) / 1000
                            println()

                            val timeStr = if (secs >= 60) "${secs/60}m ${secs%60}s" else "${secs}s"
                            println("  ${PINE}${B}\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501${R}")
                            println("  ${PINE}${B}\u2713${R} ${B}${CHALK}Research complete${R}  ${DIM}$timeStr \u00B7 $sourceCount sources \u00B7 ${event.state.finalReport.length / 1000}k chars${R}")
                            println()

                            println("  ${JADE}${B}\u2501\u2501\u2501${R} ${B}${CHALK}report${R}")
                            println("  ${DIM}${"─".repeat(44)}${R}")
                            println()

                            streamReport(event.state.finalReport)

                            println()
                            println("  ${DIM}${"─".repeat(50)}${R}")
                            println("  ${DIM}${event.state.finalReport.length} chars \u00B7 $sourceCount sources \u00B7 $timeStr${R}")
                            println("  ${JADE}${B}delve${R} ${DIM}\u2014 deep research, locally${R}")

                            val file = saveReport(displayQuery, event.state.finalReport, secs, sourceCount)
                            println()
                            println("  ${JADE}\u2713${R} Report saved ${DIM}${file.absolutePath}${R}")

                            if (copyToClipboard(event.state.finalReport)) {
                                println("  ${JADE}\u2713${R} Copied to clipboard")
                            }

                            print("\u0007")
                            System.out.flush()

                            completedState = event.state
                            lastReport = event.state.finalReport

                            if (config.memoryEnabled) {
                                researchMemory.prepareSession()
                                launch {
                                    try {
                                        val facts = FactExtractor(activeConfig, executor).extractFacts(event.state)
                                        researchMemory.saveSession(
                                            query = displayQuery,
                                            model = selectedModel.name,
                                            facts = facts,
                                            reportPath = file.absolutePath
                                        )
                                        println("  ${JADE}\u2713${R} ${DIM}${facts.size} facts saved to memory${R}")
                                    } catch (_: Exception) {
                                        println("  ${DIM}Fact extraction skipped${R}")
                                    }
                                }
                            }

                            println()
                        }
                        is DeepResearchEvent.Error -> {
                            println()
                            println("  ${RED}${B}\u2717 Error:${R} ${event.message}")
                        }
                    }
                }
                .collect()
            stopSpinner()

            if (clarificationQuestion != null) {
                println()
                println("  ${BMAG}${B}? Quick question:${R}")
                println()
                wrapText(clarificationQuestion, 64).forEach { line ->
                    if (line.isNotBlank()) println("    ${WHT}$line${R}")
                }
                println()
                print("  ${BCYN}\u276F${R} ")
                val answer = readLine()?.trim()

                activeQuery = if (answer.isNullOrBlank()) {
                    println("  ${D}Proceeding with broad research...${R}")
                    "$researchQuery\n\nResearch this topic thoroughly. Cover all relevant aspects."
                } else {
                    println("  ${JADE}\u2713${R} Got it")
                    "$researchQuery\n\nAdditional context: $answer"
                }

                activeConfig = config.copy(enableClarification = false)
                println()
                println("  ${D}${"─".repeat(40)}${R}")
                continue
            }

            break
        }

        if (completedState != null && lastReport.isNotBlank()) {
            val followUpRouter = FollowUpRouter(activeConfig, executor, rag)
            val conversationHistory = mutableListOf<ConversationTurn>()
            var currentReport = lastReport

            while (true) {
                println()
                println("  ${B}${CHALK}Follow up?${R} ${DIM}(enter to exit, /sources, /save, /export, /forget, /memory, /key)${R}")
                print("  ${PINE}\u276F${R} ")
                System.out.flush()

                val followUpInput = readLine()?.trim()
                if (followUpInput.isNullOrBlank()) {
                    println("  ${D}Session complete.${R}")
                    break
                }

                when (followUpInput.lowercase()) {
                    "/exit", "exit", "quit" -> {
                        println("  ${D}Session complete.${R}")
                        break
                    }
                    "/sources" -> {
                        val urls = Regex("""https?://[^\s\])"'>]+""")
                            .findAll(currentReport)
                            .map { it.value.trimEnd('.', ',', ')', ']') }
                            .distinct()
                            .toList()
                        if (urls.isEmpty()) {
                            println("  ${DIM}No sources found in report${R}")
                        } else {
                            println()
                            urls.forEachIndexed { i, url ->
                                println("  ${DIM}[${i + 1}]${R} $url")
                            }
                        }
                        continue
                    }
                    "/save" -> {
                        val file = saveReport(displayQuery, currentReport, 0, 0)
                        println("  ${JADE}\u2713${R} Report saved ${DIM}${file.absolutePath}${R}")
                        continue
                    }
                    "/export" -> {
                        if (copyToClipboard(currentReport)) {
                            println("  ${JADE}\u2713${R} Copied to clipboard")
                        } else {
                            println("  ${RED}\u2717${R} Clipboard copy failed")
                        }
                        continue
                    }
                    "/key" -> {
                        print("  ${B}Enter new TAVILY_API_KEY:${R} ")
                        System.out.flush()
                        val newKey = readLine()?.trim()
                        if (newKey.isNullOrBlank()) {
                            println("  ${DIM}Cancelled${R}")
                        } else {
                            userConfig = userConfig.copy(tavilyApiKey = newKey)
                            saveConfig(userConfig)
                            activeConfig = activeConfig.copy(tavilyApiKey = newKey)
                            val masked = newKey.take(4) + "*".repeat(maxOf(0, newKey.length - 4))
                            println("  ${JADE}\u2713${R} Tavily key updated ${D}($masked)${R}")
                        }
                        continue
                    }
                    "/memory" -> {
                        val stats = researchMemory.getStats()
                        println()
                        println("  ${B}Memory Stats${R}")
                        println("  ${DIM}Sessions: ${stats.sessionCount} | Facts: ${stats.totalFacts} | Indexed: ${stats.indexedFacts}${R}")
                        if (stats.recentSessions.isNotEmpty()) {
                            println()
                            println("  ${B}Recent Sessions:${R}")
                            for (s in stats.recentSessions) {
                                println("  ${DIM}[${s.timestamp.take(10)}]${R} ${s.query.take(60)} ${DIM}(${s.factCount} facts)${R}")
                            }
                        }
                        continue
                    }
                    "/forget" -> {
                        val sessionId = researchMemory.currentSessionId
                        if (sessionId != null) {
                            researchMemory.forgetCurrentSession(sessionId)
                        }
                        memoryContext = ""
                        println("  ${YLW}\u2717${R} Session memory cleared")
                        continue
                    }
                }

                var spinnerJob: Job? = null
                val decision = try {
                    spinnerJob = launchSpinner("Thinking...")
                    followUpRouter.classify(
                        followUp = followUpInput,
                        originalQuery = displayQuery,
                        currentReport = currentReport,
                        conversationHistory = conversationHistory
                    )
                } finally {
                    spinnerJob?.cancel()
                    spinnerJob = null
                }

                println("  ${DIM}[${decision.action}]${R}")

                val action = try { FollowUpAction.valueOf(decision.action.lowercase()) } catch (_: Exception) { FollowUpAction.synthesize }

                val response = try {
                    spinnerJob = launchSpinner(when (action) {
                        FollowUpAction.synthesize -> "Synthesizing..."
                        FollowUpAction.rewrite -> "Rewriting..."
                        FollowUpAction.deeper -> "Researching deeper..."
                        FollowUpAction.research -> "Conducting new research..."
                    })
                    when (action) {
                        FollowUpAction.synthesize -> {
                            followUpRouter.executeSynthesize(
                                followUpInput, displayQuery, currentReport, conversationHistory
                            )
                        }
                        FollowUpAction.rewrite -> {
                            val r = followUpRouter.executeRewrite(followUpInput, currentReport)
                            currentReport = r
                            r
                        }
                        FollowUpAction.deeper -> {
                            val newState = followUpRouter.executeDeeper(
                                focus = decision.focus.ifBlank { followUpInput },
                                originalQuery = displayQuery,
                                existingState = completedState,
                                memoryContext = memoryContext
                            )
                            if (newState.finalReport.isNotBlank()) {
                                currentReport = newState.finalReport
                            }
                            newState.finalReport.ifBlank { "No additional findings." }
                        }
                        FollowUpAction.research -> {
                            val newState = followUpRouter.executeResearch(
                                refinedQuery = decision.refinedQuery.ifBlank { followUpInput },
                                memoryContext = memoryContext
                            )
                            if (newState.finalReport.isNotBlank()) {
                                currentReport = newState.finalReport
                            }
                            newState.finalReport.ifBlank { "Research produced no results." }
                        }
                    }
                } finally {
                    spinnerJob?.cancel()
                    spinnerJob = null
                }
                println()
                streamReport(response)
                println()

                conversationHistory.add(ConversationTurn(
                    userMessage = followUpInput,
                    response = response,
                    action = action
                ))
                shutdownConversationHistory.set(conversationHistory.toList())

                if (config.memoryEnabled && researchMemory.currentSessionId != null) {
                    launch {
                        try {
                            val followUpState = ai.kash.delve.state.AgentState().apply { finalReport = response }
                            val facts = FactExtractor(activeConfig, executor).extractFacts(followUpState)
                            if (facts.isNotEmpty()) {
                                researchMemory.appendFacts(researchMemory.currentSessionId!!, facts)
                            }
                        } catch (e: Exception) {
                            System.err.println("Background fact extraction failed: ${e.message}")
                        }
                    }
                }
            }

            if (config.memoryEnabled && conversationHistory.isNotEmpty()) {
                val sessionId = researchMemory.currentSessionId
                if (sessionId != null) {
                    researchMemory.saveConversationTurns(sessionId, conversationHistory)
                }
            }
        }
    } catch (e: Exception) {
        println()
        println("  ${RED}${B}\u2717 Research failed:${R} ${e.message}")
        println()
        println("  ${D}Troubleshooting:${R}")
        println("  ${D}  1. Is Ollama still running? ${B}ollama serve${R}")
        println("  ${D}  2. Is the model available?  ${B}ollama list${R}")
        println("  ${D}  3. Check logs: /tmp/delve.log${R}")
    } finally {
        if (mcpServerConfigs.isNotEmpty()) {
            ai.kash.delve.tools.MCPToolsLoader.cleanup()
        }
        try { executor.close() } catch (_: Exception) {}
        completedNormallyRef.set(true)
        kotlin.system.exitProcess(0)
    }
}

private fun checkOllama(): Boolean {
    val whichCmd = if (System.getProperty("os.name").lowercase().contains("win")) "where" else "which"
    val ollamaInstalled = try {
        val process = ProcessBuilder(whichCmd, "ollama").start()
        process.waitFor() == 0
    } catch (_: Exception) {
        false
    }

    if (!ollamaInstalled) return false

    return try {
        httpGet("http://localhost:11434/api/tags")
        println("  ${JADE}\u2713${R} Ollama running")
        true
    } catch (e: Exception) {
        println("  ${YLW}\u26A0${R} Ollama installed but not running")
        println()
        print("  ${B}Start Ollama now?${R} ${D}[Y/n]${R} ")
        val answer = readlnOrNull()?.trim()?.lowercase()
        if (answer.isNullOrBlank() || answer == "y" || answer == "yes") {
            try {
                ProcessBuilder("ollama", "serve").redirectErrorStream(true).start()
                print("  ${D}Starting")
                repeat(3) { print("."); Thread.sleep(1000) }
                println(R)

                return try {
                    httpGet("http://localhost:11434/api/tags")
                    println("  ${JADE}\u2713${R} Ollama started")
                    true
                } catch (_: Exception) {
                    println("  ${RED}\u2717${R} Failed to start. Run manually: ${B}ollama serve${R}")
                    false
                }
            } catch (e2: Exception) {
                println("  ${RED}\u2717${R} ${e2.message}")
            }
        }
        false
    }
}

private val ollamaJson = Json { ignoreUnknownKeys = true; isLenient = true }

private suspend fun listOllamaModels(): List<OllamaModelInfo> {
    return try {
        val body = withContext(Dispatchers.IO) { httpGet("http://localhost:11434/api/tags") }
        val modelList = ollamaJson.decodeFromString<OllamaModelList>(body)
        modelList.models
            .filter { it.isUsableLLM }
            .sortedWith(compareBy<OllamaModelInfo> { it.isCloud }.thenByDescending { it.size })
    } catch (e: Exception) {
        System.err.println("Failed to list models: ${e.javaClass.simpleName}: ${e.message}")
        emptyList()
    }
}

private suspend fun queryModelContextLength(modelName: String): Long {
    return try {
        val conn = withContext(Dispatchers.IO) {
            val c = java.net.URI("http://localhost:11434/api/show").toURL().openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write("""{"name":"$modelName"}""".toByteArray()) }
            c
        }
        val body = withContext(Dispatchers.IO) { conn.inputStream.bufferedReader().use { it.readText() } }
        withContext(Dispatchers.IO) { conn.disconnect() }
        val ctxMatch = Regex(""""context_length"\s*:\s*(\d+)""").find(body)
            ?: Regex("""num_ctx\s+(\d+)""").find(body)
        ctxMatch?.groupValues?.get(1)?.toLongOrNull() ?: 8192L
    } catch (_: Exception) {
        8192L
    }
}

private const val CURSOR_UP = "\u001B[A"
private const val CLEAR_LINE = "\u001B[2K"
private const val CURSOR_HIDE = "\u001B[?25l"
private const val CURSOR_SHOW = "\u001B[?25h"

private val sttyAvailable: Boolean by lazy {
    try {
        ProcessBuilder("which", "stty")
            .redirectErrorStream(true).start().waitFor() == 0
    } catch (_: Exception) { false }
}

private fun setRawMode(): Boolean {
    if (!sttyAvailable) return false
    return try {
        ProcessBuilder("stty", "raw", "-echo")
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .start().waitFor() == 0
    } catch (_: Exception) { false }
}

private fun restoreTerminal() {
    try {
        ProcessBuilder("stty", "cooked", "echo")
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .start().waitFor()
    } catch (_: Exception) {}
}

private fun readMaskedInput(): String? {
    System.out.flush()

    if (!setRawMode()) {
        return readLine()?.trim()
    }

    val buf = StringBuilder()
    val stream = System.`in`
    try {
        while (true) {
            val b = stream.read()
            if (b == -1) break
            when (b) {
                13, 10 -> {
                    restoreTerminal()
                    return buf.toString().takeIf { it.isNotBlank() }
                }
                127, 8 -> {
                    if (buf.isNotEmpty()) {
                        buf.deleteCharAt(buf.length - 1)
                        print("\b \b")
                        System.out.flush()
                    }
                }
                3 -> {
                    restoreTerminal()
                    println()
                    Runtime.getRuntime().exit(0)
                    return null
                }
                else -> {
                    if (b in 32..126) {
                        buf.append(b.toChar())
                        print("*")
                        System.out.flush()
                    }
                }
            }
        }
    } catch (_: Exception) {
        restoreTerminal()
    }
    restoreTerminal()
    return buf.toString().takeIf { it.isNotBlank() }
}

private fun selectModel(models: List<OllamaModelInfo>): OllamaModelInfo {
    println()
    println("  ${B}${CHALK}Models${R} ${DIM}(\u2191\u2193 to move, Enter to select)${R}")
    println()

    if (!setRawMode()) {
        return selectModelFallback(models)
    }

    var selected = 0
    val stream = System.`in`

    try {
        print(CURSOR_HIDE)
        renderModelList(models, selected)

        while (true) {
            val b = stream.read()
            if (b == -1) break

            when (b) {
                13, 10 -> {
                    print("\r${CLEAR_LINE}")
                    print(CURSOR_SHOW)
                    restoreTerminal()
                    println()
                    println("  ${JADE}\u2713${R} ${models[selected].name}")
                    return models[selected]
                }
                27 -> {
                    if (stream.available() > 0) {
                        val next = stream.read()
                        if (next == 91) {
                            when (stream.read()) {
                                65 -> {
                                    if (selected > 0) {
                                        selected--
                                        redrawModelList(models, selected)
                                    }
                                }
                                66 -> {
                                    if (selected < models.size - 1) {
                                        selected++
                                        redrawModelList(models, selected)
                                    }
                                }
                            }
                        }
                    } else {
                        print(CURSOR_SHOW)
                        restoreTerminal()
                        println()
                        println("  ${JADE}\u2713${R} ${models[0].name}")
                        return models[0]
                    }
                }
                3 -> {
                    print(CURSOR_SHOW)
                    restoreTerminal()
                    println()
                    Runtime.getRuntime().exit(0)
                }
                113 -> {
                    print(CURSOR_SHOW)
                    restoreTerminal()
                    println()
                    Runtime.getRuntime().exit(0)
                }
            }
        }
    } catch (_: Exception) {
        print(CURSOR_SHOW)
        restoreTerminal()
    }

    restoreTerminal()
    print(CURSOR_SHOW)
    println("  ${JADE}\u2713${R} ${models[0].name}")
    return models[0]
}

private fun renderModelList(models: List<OllamaModelInfo>, selected: Int) {
    models.forEachIndexed { index, model ->
        val label = model.sizeLabel
        val sizeStr = if (label.isNotEmpty()) {
            if (model.isCloud) "${PINE}${label}${R}" else "${DIM}${label}${R}"
        } else ""

        if (index == selected) {
            print("  ${PINE}\u25B8${R} ${B}${CHALK}${model.name}${R} $sizeStr\r\n")
        } else {
            print("    ${DIM}${model.name}${R} $sizeStr\r\n")
        }
    }
}

private fun redrawModelList(models: List<OllamaModelInfo>, selected: Int) {
    repeat(models.size) { print("${CURSOR_UP}") }
    renderModelList(models, selected)
}

private fun selectModelFallback(models: List<OllamaModelInfo>): OllamaModelInfo {
    models.forEachIndexed { index, model ->
        val num = "${D}${index + 1}${R}"
        val label = model.sizeLabel
        val sizeStr = if (label.isNotEmpty()) {
            if (model.isCloud) "${BMAG}${label}${R}" else "${D}${label}${R}"
        } else ""
        val rec = if (index == 0) " ${D}\u2190 default${R}" else ""
        println("  $num  ${BWHT}${model.name}${R} $sizeStr$rec")
    }
    println()

    while (true) {
        print("  ${B}Select${R} ${D}[1]${R}${B}:${R} ")
        val input = readLine()?.trim()

        if (input.isNullOrBlank()) {
            println("  ${JADE}\u2713${R} ${models[0].name}")
            return models[0]
        }

        val choice = input.toIntOrNull()
        if (choice != null && choice in 1..models.size) {
            println("  ${JADE}\u2713${R} ${models[choice - 1].name}")
            return models[choice - 1]
        }

        val byName = models.find { it.name.contains(input, ignoreCase = true) }
        if (byName != null) {
            println("  ${JADE}\u2713${R} ${byName.name}")
            return byName
        }

        println("  ${RED}\u2717${R} Not found. Enter 1-${models.size} or a model name.")
    }
}

private fun promptForQuery(): String? {
    println()
    println("  ${B}${CHALK}What would you like to research?${R} ${DIM}(type @ to attach files)${R}")
    println()
    print("  ${PINE}\u276F${R} ")
    System.out.flush()

    if (!setRawMode()) {
        val input = readLine()?.trim()
        if (input.isNullOrBlank() || input.equals("quit", ignoreCase = true) || input.equals("exit", ignoreCase = true)) {
            println("  ${D}Goodbye.${R}")
            println()
            return null
        }
        return input
    }

    val buf = StringBuilder()
    val stream = System.`in`
    var suggestionsShown = 0
    filePicker.reset()

    fun refreshSuggestions() {
        clearSuggestionsBelow(suggestionsShown)
        suggestionsShown = if (isInFileRef(buf.toString())) {
            renderSuggestionsBelow(buf.toString())
        } else {
            filePicker.reset()
            0
        }
    }

    try {
        while (true) {
            val b = stream.read()
            if (b == -1) break

            when (b) {
                10, 13 -> {
                    if (filePicker.selectedIndex >= 0 && filePicker.currentMatches.isNotEmpty()) {
                        clearSuggestionsBelow(suggestionsShown)
                        acceptPickerSelection(buf)
                        redrawInputLine(buf)
                        refreshSuggestions()
                        continue
                    }
                    clearSuggestionsBelow(suggestionsShown)
                    suggestionsShown = 0
                    print("\r\n")
                    System.out.flush()
                    break
                }
                127, 8 -> {
                    if (buf.isNotEmpty()) {
                        buf.deleteCharAt(buf.length - 1)
                        filePicker.selectedIndex = -1
                        redrawInputLine(buf)
                        refreshSuggestions()
                    }
                }
                9 -> {
                    clearSuggestionsBelow(suggestionsShown)
                    handleTabComplete(buf)
                    redrawInputLine(buf)
                    refreshSuggestions()
                }
                3 -> {
                    clearSuggestionsBelow(suggestionsShown)
                    filePicker.reset()
                    print("\r\n")
                    System.out.flush()
                    restoreTerminal()
                    return null
                }
                27 -> {
                    if (stream.available() > 0) {
                        val second = stream.read()
                        if (second == 91 && stream.available() > 0) {
                            val arrow = stream.read()
                            if (filePicker.currentMatches.isNotEmpty() && isInFileRef(buf.toString())) {
                                val maxIdx = minOf(filePicker.currentMatches.size - 1, 7)
                                when (arrow) {
                                    65 -> {
                                        filePicker.selectedIndex = when {
                                            filePicker.selectedIndex <= 0 -> maxIdx
                                            else -> filePicker.selectedIndex - 1
                                        }
                                        clearSuggestionsBelow(suggestionsShown)
                                        suggestionsShown = renderSuggestionsBelow(buf.toString())
                                    }
                                    66 -> {
                                        filePicker.selectedIndex = when {
                                            filePicker.selectedIndex >= maxIdx -> 0
                                            else -> filePicker.selectedIndex + 1
                                        }
                                        clearSuggestionsBelow(suggestionsShown)
                                        suggestionsShown = renderSuggestionsBelow(buf.toString())
                                    }
                                }
                            }
                        }
                    } else {
                        if (filePicker.currentMatches.isNotEmpty()) {
                            clearSuggestionsBelow(suggestionsShown)
                            filePicker.reset()
                            suggestionsShown = 0
                        }
                    }
                }
                else -> {
                    if (b >= 32) {
                        buf.append(b.toChar())
                        filePicker.selectedIndex = -1
                        redrawInputLine(buf)
                        refreshSuggestions()
                    }
                }
            }
        }
    } finally {
        restoreTerminal()
    }

    val input = buf.toString().trim()
    if (input.isBlank() || input.equals("quit", ignoreCase = true) || input.equals("exit", ignoreCase = true)) {
        println("  ${D}Goodbye.${R}")
        println()
        return null
    }
    return input
}

private fun redrawInputLine(buf: StringBuilder) {
    print("\r\u001B[2K  ${PINE}\u276F${R} $buf")
    System.out.flush()
}

private class FilePickerState {
    var selectedIndex: Int = -1
    var currentMatches: List<FileMatch> = emptyList()
    var linesRendered: Int = 0

    fun reset() {
        selectedIndex = -1
        currentMatches = emptyList()
    }
}

private data class FileMatch(
    val relativePath: String,        // path to insert (e.g. "src/main.kt")
    val displayName: String,         // name shown in picker
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val extension: String
)

private val filePicker = FilePickerState()

private fun isInFileRef(text: String): Boolean {
    val lastAt = text.lastIndexOf('@')
    if (lastAt < 0) return false
    val afterAt = text.substring(lastAt + 1)
    return !afterAt.contains(' ')
}

private fun extractPartialRef(text: String): String? {
    val lastAt = text.lastIndexOf('@')
    if (lastAt < 0) return null
    val afterAt = text.substring(lastAt + 1)
    if (afterAt.contains(' ')) return null
    return afterAt
}

private fun fileIcon(match: FileMatch): String {
    if (match.isDirectory) return "${JADE}\uD83D\uDCC1${R}"
    return when (match.extension.lowercase()) {
        "kt", "kts"       -> "${MAG}\uD83D\uDCDC${R}"
        "java"             -> "${RED}\u2615${R}"
        "py"               -> "${YLW}\uD83D\uDC0D${R}"
        "js", "ts", "tsx"  -> "${YLW}\u26A1${R}"
        "json"             -> "${CYN}\u007B\u007D${R}"
        "yaml", "yml"      -> "${BGRN}\u2699${R}"
        "md", "txt"        -> "${CHALK}\uD83D\uDCC4${R}"
        "pdf"              -> "${RED}\uD83D\uDCC4${R}"
        "csv", "tsv"       -> "${GRN}\uD83D\uDCCA${R}"
        "xml", "html"      -> "${BBLU}\u003C\u003E${R}"
        "sh", "bash", "zsh"-> "${GRN}\u0024${R}"
        "toml", "ini", "cfg" -> "${DIM}\u2699${R}"
        "png", "jpg", "jpeg", "gif", "svg" -> "${BMAG}\uD83D\uDDBC${R}"
        else               -> "${DIM}\uD83D\uDCC4${R}"
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024       -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}K"
    else               -> "${"%.1f".format(bytes / (1024.0 * 1024.0))}M"
}

private fun renderSuggestionsBelow(text: String): Int {
    val partial = extractPartialRef(text) ?: return 0
    val matches = listMatchingFiles(partial)
    filePicker.currentMatches = matches
    if (matches.isEmpty()) {
        filePicker.selectedIndex = -1
        return 0
    }

    if (filePicker.selectedIndex >= matches.size) filePicker.selectedIndex = matches.size - 1

    val maxShow = 8
    val display = matches.take(maxShow)
    val hasMore = matches.size > maxShow

    print("\r\n\u001B[2K    ${DIM}\u250C${"─".repeat(50)}${R}")

    for ((i, match) in display.withIndex()) {
        val icon = fileIcon(match)
        val selected = i == filePicker.selectedIndex
        val pointer = if (selected) "${PINE}\u25B6${R}" else " "
        val name = if (selected) {
            if (match.isDirectory) "${B}${JADE}${match.displayName}/${R}" else "${B}${CHALK}${match.displayName}${R}"
        } else {
            if (match.isDirectory) "${JADE}${match.displayName}/${R}" else "${CHALK}${match.displayName}${R}"
        }
        val size = if (match.isDirectory) "${DIM}dir${R}" else "${DIM}${humanSize(match.sizeBytes)}${R}"
        val bg = if (selected) "\u001B[48;5;236m" else ""
        val bgR = if (selected) "\u001B[49m" else ""
        print("\r\n\u001B[2K    ${DIM}\u2502${R}${bg} $pointer $icon $name  $size ${bgR}")
    }
    if (hasMore) {
        print("\r\n\u001B[2K    ${DIM}\u2502  +${matches.size - maxShow} more \u2191\u2193 to browse${R}")
    }

    val linesUsed = 1 + display.size + if (hasMore) 1 else 0

    print("\u001B[${linesUsed}A")
    print("\r\u001B[2K  ${PINE}\u276F${R} $text")
    System.out.flush()
    filePicker.linesRendered = linesUsed
    return linesUsed
}

private fun clearSuggestionsBelow(count: Int) {
    if (count == 0) return
    for (i in 0 until count) print("\r\n\u001B[2K")
    print("\u001B[${count}A")
    System.out.flush()
}

private fun handleTabComplete(buf: StringBuilder) {
    val partial = extractPartialRef(buf.toString()) ?: return
    val matches = filePicker.currentMatches.ifEmpty { listMatchingFiles(partial) }
    if (matches.isEmpty()) return

    if (filePicker.selectedIndex >= 0 && filePicker.selectedIndex < matches.size) {
        val selected = matches[filePicker.selectedIndex]
        val toAppend = selected.relativePath.removePrefix(partial)
        buf.append(toAppend)
        if (!selected.isDirectory) buf.append(' ')
        filePicker.reset()
        return
    }

    if (matches.size == 1) {
        val toAppend = matches[0].relativePath.removePrefix(partial)
        buf.append(toAppend)
        if (!matches[0].isDirectory) buf.append(' ')
        filePicker.reset()
        return
    }

    val commonPrefix = matches.map { it.relativePath }.reduce { a, b -> a.commonPrefixWith(b) }
    if (commonPrefix.length > partial.length) {
        buf.append(commonPrefix.removePrefix(partial))
    }
    if (filePicker.selectedIndex < 0) filePicker.selectedIndex = 0
}

private fun acceptPickerSelection(buf: StringBuilder): Boolean {
    if (filePicker.selectedIndex < 0 || filePicker.currentMatches.isEmpty()) return false
    val selected = filePicker.currentMatches[filePicker.selectedIndex]
    val partial = extractPartialRef(buf.toString()) ?: return false
    val toAppend = selected.relativePath.removePrefix(partial)
    buf.append(toAppend)
    if (!selected.isDirectory) buf.append(' ')
    filePicker.reset()
    return true
}

private fun listMatchingFiles(partial: String): List<FileMatch> {
    val expanded = partial.replace("~", System.getProperty("user.home"))

    val dir: File
    val prefix: String

    if (expanded.isEmpty()) {
        dir = File(".")
        prefix = ""
    } else {
        val f = File(expanded)
        if (f.isDirectory && (partial.endsWith("/") || partial.isEmpty())) {
            dir = f
            prefix = ""
        } else {
            dir = f.parentFile ?: File(".")
            prefix = f.name
        }
    }

    if (!dir.isDirectory) return emptyList()

    val entries = dir.listFiles()
        ?.filter { !it.name.startsWith(".") }
        ?.filter { it.name.startsWith(prefix, ignoreCase = true) }
        ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        ?.take(20)
        ?: return emptyList()

    val dirPrefix = if (partial.contains("/")) {
        partial.substringBeforeLast("/") + "/"
    } else ""

    return entries.map { file ->
        FileMatch(
            relativePath = "$dirPrefix${file.name}" + if (file.isDirectory) "/" else "",
            displayName = file.name,
            isDirectory = file.isDirectory,
            sizeBytes = if (file.isFile) file.length() else 0,
            extension = file.extension
        )
    }
}

private fun printSessionHeader(query: String, model: String, depth: String, mcpServers: Set<String> = emptySet(), attachedFiles: List<String> = emptyList()) {
    val displayQuery = query.lines().first().trim()
    val queryLines = wrapText(displayQuery, 60)

    println("  ${PINE}${B}\u2501\u2501\u2501${R} ${DIM}session${R}")
    println()
    queryLines.forEachIndexed { i, line ->
        if (i == 0) {
            println("  ${B}${CHALK}$line${R}")
        } else {
            println("  ${CHALK}$line${R}")
        }
    }
    println()
    println("  ${JADE}\u25AA${R} ${DIM}model${R}  ${CHALK}$model${R}")
    println("  ${JADE}\u25AA${R} ${DIM}search${R} ${CHALK}Tavily${R}")
    println("  ${JADE}\u25AA${R} ${DIM}depth${R}  ${PINE}$depth${R}")
    if (attachedFiles.isNotEmpty()) {
        println("  ${JADE}\u25AA${R} ${DIM}files${R}  ${CHALK}${attachedFiles.joinToString("${DIM}, ${CHALK}")}${R}")
    }
    if (mcpServers.isNotEmpty()) {
        println("  ${JADE}\u25AA${R} ${DIM}mcp${R}    ${JADE}${mcpServers.joinToString("${DIM}, ${JADE}")}${R}")
    }
    println()
    println("  ${DIM}${"─".repeat(44)}${R}")
}

data class ResearchDepth(
    val label: String,
    val iterations: Int,
    val toolCalls: Int,
    val concurrency: Int
)

private val DEPTHS = listOf(
    ResearchDepth("Quick",  iterations = 2,  toolCalls = 7,  concurrency = 5),
    ResearchDepth("Normal", iterations = 5,  toolCalls = 15, concurrency = 7),
    ResearchDepth("Deep",   iterations = 10, toolCalls = 25, concurrency = 10),
)

private val DEPTH_DESCRIPTIONS = arrayOf(
    "fast scan, fewer sources",
    "balanced depth and speed",
    "thorough, more iterations",
)

private fun selectDepth(): ResearchDepth {
    println()
    println("  ${B}${CHALK}Research depth${R} ${DIM}(\u2191\u2193 to move, Enter to select)${R}")
    println()

    if (!setRawMode()) {
        return selectDepthFallback()
    }

    var selected = 1
    val stream = System.`in`

    try {
        print(CURSOR_HIDE)
        renderDepthList(selected)

        while (true) {
            val b = stream.read()
            if (b == -1) break

            when (b) {
                13, 10 -> {
                    print("\r${CLEAR_LINE}")
                    print(CURSOR_SHOW)
                    restoreTerminal()
                    println()
                    println("  ${JADE}\u2713${R} ${DEPTHS[selected].label}")
                    return DEPTHS[selected]
                }
                27 -> {
                    if (stream.available() > 0) {
                        val next = stream.read()
                        if (next == 91) {
                            when (stream.read()) {
                                65 -> {
                                    if (selected > 0) {
                                        selected--
                                        redrawDepthList(selected)
                                    }
                                }
                                66 -> {
                                    if (selected < DEPTHS.size - 1) {
                                        selected++
                                        redrawDepthList(selected)
                                    }
                                }
                            }
                        }
                    } else {
                        print(CURSOR_SHOW)
                        restoreTerminal()
                        println()
                        println("  ${JADE}\u2713${R} ${DEPTHS[1].label}")
                        return DEPTHS[1]
                    }
                }
                3 -> {
                    print(CURSOR_SHOW)
                    restoreTerminal()
                    println()
                    Runtime.getRuntime().exit(0)
                }
            }
        }
    } catch (_: Exception) {
        print(CURSOR_SHOW)
        restoreTerminal()
    }

    restoreTerminal()
    print(CURSOR_SHOW)
    println("  ${JADE}\u2713${R} ${DEPTHS[1].label}")
    return DEPTHS[1]
}

private fun renderDepthList(selected: Int) {
    DEPTHS.forEachIndexed { index, depth ->
        val desc = "${DIM}\u2014 ${DEPTH_DESCRIPTIONS[index]}${R}"
        if (index == selected) {
            print("  ${PINE}\u25B8${R} ${B}${CHALK}${depth.label}${R}   $desc\r\n")
        } else {
            print("    ${DIM}${depth.label}${R}   $desc\r\n")
        }
    }
}

private fun redrawDepthList(selected: Int) {
    repeat(DEPTHS.size) { print(CURSOR_UP) }
    renderDepthList(selected)
}

private fun selectDepthFallback(): ResearchDepth {
    println("    ${D}1${R}  ${BWHT}Quick${R}   ${D}\u2014 fast scan, fewer sources${R}")
    println("    ${D}2${R}  ${BWHT}Normal${R}  ${D}\u2014 balanced depth and speed${R}")
    println("    ${D}3${R}  ${BWHT}Deep${R}    ${D}\u2014 thorough, more iterations${R}")
    println()
    print("  ${B}Select${R} ${D}[2]${R}${B}:${R} ")

    val input = readLine()?.trim()
    val choice = when {
        input.isNullOrBlank() -> 1
        input == "1" || input.startsWith("q", true) -> 0
        input == "2" || input.startsWith("n", true) -> 1
        input == "3" || input.startsWith("d", true) -> 2
        else -> 1
    }
    println("  ${JADE}\u2713${R} ${DEPTHS[choice].label}")
    return DEPTHS[choice]
}

private fun wrapText(text: String, maxWidth: Int): List<String> {
    val result = mutableListOf<String>()
    for (line in text.lines()) {
        if (line.length <= maxWidth) {
            result.add(line)
            continue
        }
        val words = line.split(" ")
        val current = StringBuilder()
        for (word in words) {
            if (current.isEmpty()) {
                current.append(word)
            } else if (current.length + 1 + word.length <= maxWidth) {
                current.append(" ").append(word)
            } else {
                result.add(current.toString())
                current.clear().append(word)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
    }
    return result
}

private fun streamReport(report: String) {
    val lines = report.lines()
    var inCodeBlock = false

    for ((lineIdx, rawLine) in lines.withIndex()) {
        val line = rawLine

        if (line.trimStart().startsWith("```")) {
            inCodeBlock = !inCodeBlock
            println("  ${D}${line}${R}")
            Thread.sleep(8)
            continue
        }

        if (inCodeBlock) {
            println("  ${D}${line}${R}")
            continue
        }

        when {
            line.startsWith("# ") -> {
                if (lineIdx > 0) { println(); Thread.sleep(20) }
                val text = line.removePrefix("# ")
                print("  ${B}${CHALK}")
                streamChars(text, 3)
                println(R)
                println("  ${DIM}${"─".repeat(minOf(text.length + 4, 50))}${R}")
                Thread.sleep(20)
            }
            line.startsWith("## ") -> {
                if (lineIdx > 0) println()
                val text = line.removePrefix("## ")
                print("  ${B}${PINE}")
                streamChars(text, 3)
                println(R)
                Thread.sleep(15)
            }
            line.startsWith("### ") -> {
                val text = line.removePrefix("### ")
                print("  ${B}${JADE}")
                streamChars(text, 2)
                println(R)
                Thread.sleep(10)
            }
            line.trim().matches(Regex("^-{3,}$|^\\*{3,}$|^_{3,}$")) -> {
                println("  ${DIM}${"─".repeat(40)}${R}")
                Thread.sleep(8)
            }
            line.isBlank() -> {
                println()
                Thread.sleep(5)
            }
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                val indent = line.length - line.trimStart().length
                val content = line.trimStart().drop(2)
                val rendered = renderInlineMarkdown(content)
                print("  ${" ".repeat(indent)}${PINE}\u2022${R} ")
                streamWords(rendered, 4)
                println()
            }
            line.trimStart().matches(Regex("^\\d+[.)].+")) -> {
                val indent = line.length - line.trimStart().length
                val match = Regex("^(\\d+[.)]) (.+)").find(line.trimStart())
                if (match != null) {
                    val num = match.groupValues[1]
                    val content = match.groupValues[2]
                    val rendered = renderInlineMarkdown(content)
                    print("  ${" ".repeat(indent)}${D}$num${R} ")
                    streamWords(rendered, 4)
                    println()
                } else {
                    print("  ")
                    streamWords(renderInlineMarkdown(line), 4)
                    println()
                }
            }
            else -> {
                val rendered = renderInlineMarkdown(line)
                print("  ")
                streamWords(rendered, 4)
                println()
            }
        }
    }
}

private fun renderInlineMarkdown(text: String): String {
    var result = text
    // Bold: **text**
    result = result.replace(Regex("\\*\\*(.+?)\\*\\*"), "${B}$1${R}")
    // Inline code: `code`
    result = result.replace(Regex("`(.+?)`"), "${D}\u001B[48;5;236m $1 ${R}")
    // Italic: *text* (but not inside **)
    result = result.replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)"), "${I}$1${R}")
    return result
}

private fun streamChars(text: String, delayMs: Long) {
    for (ch in text) {
        print(ch)
        System.out.flush()
        Thread.sleep(delayMs)
    }
}

private fun streamWords(text: String, delayMs: Long) {
    val words = text.split(" ")
    for ((i, word) in words.withIndex()) {
        print(word)
        System.out.flush()
        if (i < words.size - 1) {
            print(" ")
            Thread.sleep(delayMs)
        }
    }
}

private fun printBanner() {
    println()
    println(" ${MINT}${B} ██████╗ ███████╗██╗     ██╗   ██╗███████╗${R}")
    println(" ${MINT}${B} ██╔══██╗██╔════╝██║     ██║   ██║██╔════╝${R}")
    println(" ${JADE}${B} ██║  ██║█████╗  ██║     ██║   ██║█████╗  ${R}")
    println(" ${JADE}${B} ██║  ██║██╔══╝  ██║     ╚██╗ ██╔╝██╔══╝  ${R}")
    println(" ${PINE}${B} ██████╔╝███████╗███████╗ ╚████╔╝ ███████╗${R}")
    println(" ${DIM} ╚═════╝ ╚══════╝╚══════╝  ╚═══╝  ╚══════╝${R}")
    println()
    println("  ${DIM}Deep research, locally. Powered by Ollama.${R}")
    println()
}

private fun printOllamaSetupGuide() {
    println()
    println("  ${RED}${B}\u2717 Ollama not found${R}")
    println()
    println("  ${B}Install:${R}")
    println()
    println("    ${B}macOS${R}    brew install ollama")
    println("    ${B}Linux${R}    curl -fsSL https://ollama.ai/install.sh | sh")
    println("    ${D}or download from https://ollama.ai${R}")
    println()
    println("  ${B}Then:${R}")
    println("    ollama serve")
    println("    ollama pull llama3.1")
    println("    delve")
    println()
}

private fun printNoModelsGuide() {
    println()
    println("  ${YLW}${B}\u26A0 No models found${R}")
    println()
    println("  ${B}Pull a model:${R}")
    println()
    println("    ollama pull llama3.1       ${D}8B, good balance${R}")
    println("    ollama pull qwen2.5:14b    ${D}14B, better reasoning${R}")
    println("    ollama pull gemma2         ${D}9B, Google's model${R}")
    println()

    print("  ${B}Pull llama3.1 now?${R} ${D}[Y/n]${R} ")
    val answer = readLine()?.trim()?.lowercase()
    if (answer.isNullOrBlank() || answer == "y" || answer == "yes") {
        println()
        println("  ${D}Pulling llama3.1...${R}")
        try {
            val process = ProcessBuilder("ollama", "pull", "llama3.1")
                .inheritIO()
                .start()
            process.waitFor()
            if (process.exitValue() == 0) {
                println()
                println("  ${JADE}\u2713${R} Model ready! Run ${B}delve${R} again.")
            }
        } catch (e: Exception) {
            println("  ${RED}\u2717${R} Failed: ${e.message}")
        }
    }
    println()
}
