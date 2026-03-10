package ai.kash.delve.config

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
//todo: add native searches when adding OpenAI/Anthropic
enum class SearchAPI { TAVILY }

val DefaultOllamaModel = LLModel(
    provider = LLMProvider.Ollama,
    id = "llama3.1:8b",
    capabilities = listOf(
        LLMCapability.Temperature,
        LLMCapability.Schema.JSON.Standard,
        LLMCapability.Tools
    ),
    contextLength = 128_000,
)

enum class MCPTransport { SSE, STDIO }

data class MCPConfig(
    val url: String? = null,
    val command: List<String>? = null,
    val environment: Map<String, String> = emptyMap(),
    val tools: List<String>? = null,
    val authRequired: Boolean = false,
    val transport: MCPTransport = MCPTransport.SSE
) {
    companion object {
        fun sse(url: String, tools: List<String>? = null) = MCPConfig(
            url = url, transport = MCPTransport.SSE, tools = tools
        )

        fun stdio(
            command: List<String>,
            environment: Map<String, String> = emptyMap(),
            tools: List<String>? = null
        ) = MCPConfig(
            command = command, environment = environment,
            transport = MCPTransport.STDIO, tools = tools
        )
    }
}

data class DeepResearchConfig(
    val tavilyApiKey: String? = null,


    val clarifyModel: LLModel = DefaultOllamaModel,
    val supervisorModel: LLModel = DefaultOllamaModel,
    val researchModel: LLModel = DefaultOllamaModel,
    val compressionModel: LLModel = DefaultOllamaModel,
    val finalReportModel: LLModel = DefaultOllamaModel,
    val summarizationModel: LLModel = DefaultOllamaModel,

    val maxReactToolCalls: Int = 20,
    val maxSupervisorIterations: Int = 10,
    val maxStructuredOutputRetries: Int = 3,
    val maxConcurrentResearchUnits: Int = 5,
    val temperature: Double = 0.0,
    // Per-phase temperature overrides (null = use global temperature).
    // Lower values (0.0) are better for deterministic JSON extraction (clarify, brief).
    // Slightly higher values (0.3-0.7) can improve prose quality (report).
    val supervisorTemperature: Double? = null,
    val researchTemperature: Double? = null,

    val httpRequestTimeoutMs: Long = 60_000,
    val httpConnectTimeoutMs: Long = 10_000,
    val summarizationTimeoutMs: Long = 60_000,

    val searchApi: SearchAPI = SearchAPI.TAVILY,
    val tavilySearchMaxResults: Int = 5,
    val tavilyIncludeRawContent: Boolean = true,
    val tavilySearchDepth: String = "advanced",

    val enableClarification: Boolean = true,
    val maxContentLength: Int = 50000,
    val maxNoteLength: Int = 2000,
    val fallbackContentLength: Int = 10000,

    val mcpConfig: MCPConfig? = null,
    // Multiple MCP servers keyed by name; takes precedence over mcpConfig
    val mcpConfigs: Map<String, MCPConfig> = emptyMap(),
    val mcpPrompt: String? = null,
    val memoryEnabled: Boolean = true,
) {
    companion object {
        fun fromEnvironment() = DeepResearchConfig(
            tavilyApiKey = System.getenv("TAVILY_API_KEY")
        )

        fun forTesting() = DeepResearchConfig(
            maxReactToolCalls = 3, maxSupervisorIterations = 3,
            maxConcurrentResearchUnits = 2, enableClarification = false
        )

        fun fast() = DeepResearchConfig(
            maxReactToolCalls = 3, maxSupervisorIterations = 2,
            maxConcurrentResearchUnits = 1, enableClarification = false
        )

        fun thorough() = DeepResearchConfig(
            maxReactToolCalls = 20, maxSupervisorIterations = 10,
            tavilySearchMaxResults = 10, enableClarification = true
        )
    }
}
