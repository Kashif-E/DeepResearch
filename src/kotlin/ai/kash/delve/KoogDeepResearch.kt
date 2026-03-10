package ai.kash.delve

import ai.kash.delve.config.DeepResearchConfig
import ai.kash.delve.config.DefaultOllamaModel
import ai.kash.delve.config.MCPConfig
import ai.kash.delve.config.SearchAPI
import ai.kash.delve.graph.DeepResearchGraph
import ai.kash.delve.model.FinalReport
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

class KoogDeepResearch private constructor(
    config: DeepResearchConfig,
    promptExecutor: PromptExecutor,
    rag: ai.kash.delve.rag.DocumentRAG? = null,
    memoryContext: String = ""
) {

    private val graph =
        DeepResearchGraph(config, promptExecutor, rag, memoryContext)

    fun research(query: String): ai.kash.delve.state.AgentState = runBlocking {
        researchAsync(query)
    }

    suspend fun researchAsync(query: String): ai.kash.delve.state.AgentState {
        logger.info { "Starting deep research on: ${query.take(100)}..." }
        return graph.invoke(query)
    }

    suspend fun researchReport(query: String): FinalReport {
        val state = researchAsync(query)
        val urlPattern = Regex("""https?://[^\s\])"'>]+""")
        val sources = urlPattern.findAll(state.finalReport)
            .map { it.value.trimEnd('.', ',', ')', ']') }
            .distinct()
            .toList()
        return FinalReport(
            report = state.finalReport,
            sources = sources,
            researchBrief = state.researchBrief
        )
    }

    fun researchStream(query: String): Flow<ai.kash.delve.graph.DeepResearchEvent> {
        logger.info { "Starting streaming deep research on: ${query.take(100)}..." }
        return graph.stream(query)
    }

    companion object {
        fun create(configure: DeepResearchConfigBuilder.() -> Unit): KoogDeepResearch {
            val builder = DeepResearchConfigBuilder()
            builder.configure()
            return create(builder.build())
        }

        fun create(config: DeepResearchConfig): KoogDeepResearch {
            val promptExecutor = simpleOllamaAIExecutor()
            return KoogDeepResearch(config, promptExecutor)
        }

        fun create(
            config: DeepResearchConfig,
            promptExecutor: PromptExecutor,
            rag: ai.kash.delve.rag.DocumentRAG? = null,
            memoryContext: String = ""
        ): KoogDeepResearch {
            return KoogDeepResearch(config, promptExecutor, rag, memoryContext)
        }

        fun createFromEnvironment(): KoogDeepResearch {
            return create(DeepResearchConfig.fromEnvironment())
        }
    }
}

class DeepResearchConfigBuilder {
    var tavilyApiKey: String? = null

    var clarifyModel: LLModel = DefaultOllamaModel
    var supervisorModel: LLModel = DefaultOllamaModel
    var researchModel: LLModel = DefaultOllamaModel
    var compressionModel: LLModel = DefaultOllamaModel
    var finalReportModel: LLModel = DefaultOllamaModel
    var summarizationModel: LLModel = DefaultOllamaModel

    var maxReactToolCalls: Int = 20
    var maxSupervisorIterations: Int = 10
    var maxStructuredOutputRetries: Int = 3
    var maxConcurrentResearchUnits: Int = 5
    var temperature: Double = 0.0
    var supervisorTemperature: Double? = null
    var researchTemperature: Double? = null

    var httpRequestTimeoutMs: Long = 60_000
    var httpConnectTimeoutMs: Long = 10_000
    var summarizationTimeoutMs: Long = 60_000

    var searchApi: SearchAPI = SearchAPI.TAVILY
    var tavilySearchMaxResults: Int = 5
    var tavilyIncludeRawContent: Boolean = true
    var tavilySearchDepth: String = "advanced"

    var enableClarification: Boolean = true
    var maxContentLength: Int = 50000
    var maxNoteLength: Int = 2000
    var fallbackContentLength: Int = 10000

    var mcpConfig: MCPConfig? = null
    var mcpConfigs: Map<String, MCPConfig> = emptyMap()
    var mcpPrompt: String? = null
    var memoryEnabled: Boolean = true

    fun build(): DeepResearchConfig =
        DeepResearchConfig(
            tavilyApiKey = tavilyApiKey,
            clarifyModel = clarifyModel,
            supervisorModel = supervisorModel,
            researchModel = researchModel,
            compressionModel = compressionModel,
            finalReportModel = finalReportModel,
            summarizationModel = summarizationModel,
            maxReactToolCalls = maxReactToolCalls,
            maxSupervisorIterations = maxSupervisorIterations,
            maxStructuredOutputRetries = maxStructuredOutputRetries,
            maxConcurrentResearchUnits = maxConcurrentResearchUnits,
            temperature = temperature,
            supervisorTemperature = supervisorTemperature,
            researchTemperature = researchTemperature,
            httpRequestTimeoutMs = httpRequestTimeoutMs,
            httpConnectTimeoutMs = httpConnectTimeoutMs,
            summarizationTimeoutMs = summarizationTimeoutMs,
            searchApi = searchApi,
            tavilySearchMaxResults = tavilySearchMaxResults,
            tavilyIncludeRawContent = tavilyIncludeRawContent,
            tavilySearchDepth = tavilySearchDepth,
            enableClarification = enableClarification,
            maxContentLength = maxContentLength,
            maxNoteLength = maxNoteLength,
            fallbackContentLength = fallbackContentLength,
            mcpConfig = mcpConfig,
            mcpConfigs = mcpConfigs,
            mcpPrompt = mcpPrompt,
            memoryEnabled = memoryEnabled
        )
}

suspend fun KoogDeepResearch.researchAndGetReport(query: String): String {
    val state = researchAsync(query)
    return state.finalReport
}
