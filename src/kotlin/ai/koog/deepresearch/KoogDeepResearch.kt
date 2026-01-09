package ai.koog.deepresearch

import ai.koog.deepresearch.config.DeepResearchConfig
import ai.koog.deepresearch.graph.DeepResearchEvent
import ai.koog.deepresearch.graph.DeepResearchGraph
import ai.koog.deepresearch.state.AgentState
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

/**
 * Main entry point for the Deep Research Agent.
 * 
 * This is a 1:1 port of the Open Deep Research (Python/LangGraph) implementation.
 * 
 * The agent follows a hierarchical structure:
 * 1. **Main Graph**: Orchestrates the overall research flow
 *    - clarify_with_user → write_research_brief → research_supervisor → final_report_generation
 * 
 * 2. **Supervisor Subgraph**: Coordinates research tasks
 *    - supervisor → supervisor_tools (spawns researchers) → loop or END
 * 
 * 3. **Researcher Subgraph**: Executes individual research tasks
 *    - researcher → researcher_tools → loop or compress_research → END
 * 
 * Example usage:
 * ```kotlin
 * val agent = KoogDeepResearch.create {
 *     openaiApiKey = "your-api-key"
 *     tavilyApiKey = "your-tavily-key"
 * }
 * 
 * val result = agent.research("What are the latest advances in quantum computing?")
 * println(result.finalReport)
 * ```
 */
class KoogDeepResearch private constructor(
    private val config: DeepResearchConfig,
    private val promptExecutor: PromptExecutor
) {
    
    private val graph = DeepResearchGraph(config, promptExecutor)
    
    /**
     * Execute a research query synchronously.
     * Mirrors Python: graph.invoke({"messages": [HumanMessage(query)]})
     * 
     * @param query The research question or topic
     * @return AgentState containing the final report and all research data
     */
    fun research(query: String): AgentState = runBlocking {
        researchAsync(query)
    }
    
    /**
     * Execute a research query asynchronously.
     * 
     * @param query The research question or topic
     * @return AgentState containing the final report and all research data
     */
    suspend fun researchAsync(query: String): AgentState {
        logger.info { "Starting deep research on: ${query.take(100)}..." }
        return graph.invoke(query)
    }
    
    /**
     * Execute a research query with streaming progress updates.
     * 
     * @param query The research question or topic
     * @return Flow of DeepResearchEvent for progress tracking
     */
    fun researchStream(query: String): Flow<DeepResearchEvent> {
        logger.info { "Starting streaming deep research on: ${query.take(100)}..." }
        return graph.stream(query)
    }
    
    /**
     * Get the current configuration.
     */
    fun getConfig(): DeepResearchConfig = config
    
    companion object {
        
        /**
         * Create a new Deep Research agent with the given configuration.
         * 
         * Example:
         * ```kotlin
         * val agent = KoogDeepResearch.create {
         *     openaiApiKey = System.getenv("OPENAI_API_KEY")
         *     tavilyApiKey = System.getenv("TAVILY_API_KEY")
         *     maxResearchLoops = 5
         * }
         * ```
         */
        fun create(configure: DeepResearchConfigBuilder.() -> Unit): KoogDeepResearch {
            val builder = DeepResearchConfigBuilder()
            builder.configure()
            return create(builder.build())
        }
        
        /**
         * Create a new Deep Research agent with explicit configuration.
         */
        fun create(config: DeepResearchConfig): KoogDeepResearch {
            // Create prompt executor based on provider
            val promptExecutor = createPromptExecutor(config)
            return KoogDeepResearch(config, promptExecutor)
        }
        
        /**
         * Create with default configuration from environment variables.
         * 
         * Required environment variables:
         * - OPENAI_API_KEY (or GOOGLE_API_KEY for Gemini models)
         * - TAVILY_API_KEY (for web search)
         */
        fun createFromEnvironment(): KoogDeepResearch {
            return create(DeepResearchConfig.fromEnvironment())
        }
        
        /**
         * Create prompt executor based on the configured models.
         * Detects the provider from the first model and creates the appropriate executor.
         */
        private fun createPromptExecutor(config: DeepResearchConfig): PromptExecutor {
            // Detect provider from the configured models
            val provider = config.researchModel.provider
            
            return when (provider) {
                LLMProvider.OpenAI -> {
                    val apiKey = config.openaiApiKey
                        ?: throw IllegalStateException("OPENAI_API_KEY not configured for OpenAI models")
                    simpleOpenAIExecutor(apiKey)
                }
                LLMProvider.Google -> {
                    val apiKey = config.googleApiKey
                        ?: throw IllegalStateException("GOOGLE_API_KEY not configured for Google/Gemini models")
                    simpleGoogleAIExecutor(apiKey)
                }
                else -> {
                    // Fallback: try OpenAI first, then Google
                    config.openaiApiKey?.let { return simpleOpenAIExecutor(it) }
                    config.googleApiKey?.let { return simpleGoogleAIExecutor(it) }
                    throw IllegalStateException(
                        "No API key configured. Set OPENAI_API_KEY or GOOGLE_API_KEY environment variable."
                    )
                }
            }
        }
    }
}

/**
 * Builder for configuring DeepResearchConfig.
 */
class DeepResearchConfigBuilder {
    // API Keys
    var openaiApiKey: String? = System.getenv("OPENAI_API_KEY")
    var anthropicApiKey: String? = System.getenv("ANTHROPIC_API_KEY")
    var googleApiKey: String? = System.getenv("GOOGLE_API_KEY")
    var tavilyApiKey: String? = System.getenv("TAVILY_API_KEY")
    
    // Models - defaults to OpenAI, can be switched to Google
    var clarifyModel: LLModel = OpenAIModels.Chat.GPT4oMini
    var supervisorModel: LLModel = OpenAIModels.Chat.GPT4o
    var researchModel: LLModel = OpenAIModels.Chat.GPT4oMini
    var compressionModel: LLModel = OpenAIModels.Chat.GPT4oMini
    var finalReportModel: LLModel = OpenAIModels.Chat.GPT4o
    var summarizationModel: LLModel = OpenAIModels.Chat.GPT4oMini
    
    /**
     * Configure all models to use Google Gemini.
     * Call this to switch from OpenAI to Google models.
     */
    fun useGoogleModels() {
        clarifyModel = GoogleModels.Gemini2_5Flash
        supervisorModel = GoogleModels.Gemini2_5Pro
        researchModel = GoogleModels.Gemini2_5Flash
        compressionModel = GoogleModels.Gemini2_5Flash
        finalReportModel = GoogleModels.Gemini2_5Pro
        summarizationModel = GoogleModels.Gemini2_5Flash
    }
    
    /**
     * Configure all models to use Google Gemini Flash (faster, cheaper).
     */
    fun useGoogleFlashModels() {
        clarifyModel = GoogleModels.Gemini2_0Flash
        supervisorModel = GoogleModels.Gemini2_5Flash
        researchModel = GoogleModels.Gemini2_0Flash
        compressionModel = GoogleModels.Gemini2_0Flash
        finalReportModel = GoogleModels.Gemini2_5Flash
        summarizationModel = GoogleModels.Gemini2_0Flash
    }
    
    // Research Limits
    var maxResearchLoops: Int = 5
    var maxReactToolCalls: Int = 20
    var maxSupervisorIterations: Int = 10
    
    // Search Configuration
    var tavilySearchMaxResults: Int = 5
    var tavilyIncludeRawContent: Boolean = true
    var tavilySearchDepth: String = "advanced"
    
    // Features
    var enableClarification: Boolean = true
    var mcpPrompt: String? = null
    
    fun build(): DeepResearchConfig = DeepResearchConfig(
        openaiApiKey = openaiApiKey,
        anthropicApiKey = anthropicApiKey,
        googleApiKey = googleApiKey,
        tavilyApiKey = tavilyApiKey,
        clarifyModel = clarifyModel,
        supervisorModel = supervisorModel,
        researchModel = researchModel,
        compressionModel = compressionModel,
        finalReportModel = finalReportModel,
        summarizationModel = summarizationModel,
        maxResearchLoops = maxResearchLoops,
        maxReactToolCalls = maxReactToolCalls,
        maxSupervisorIterations = maxSupervisorIterations,
        tavilySearchMaxResults = tavilySearchMaxResults,
        tavilyIncludeRawContent = tavilyIncludeRawContent,
        tavilySearchDepth = tavilySearchDepth,
        enableClarification = enableClarification,
        mcpPrompt = mcpPrompt
    )
}

/**
 * Extension function for convenient research execution.
 */
suspend fun KoogDeepResearch.researchAndGetReport(query: String): String {
    val state = researchAsync(query)
    return state.finalReport
}
