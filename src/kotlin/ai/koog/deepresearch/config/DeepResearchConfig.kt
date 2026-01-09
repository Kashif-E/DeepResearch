package ai.koog.deepresearch.config

import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.executor.clients.openai.OpenAIModels

/**
 * Search API providers supported by Deep Research.
 * Mirrors Python configuration.SearchAPI enum.
 */
enum class SearchAPI {
    TAVILY,         // Tavily Search API (recommended)
    OPENAI,         // OpenAI native web search (requires compatible model)
    ANTHROPIC,      // Anthropic native web search (requires compatible model)
    GOOGLE,         // Google Search API
    NONE            // No web search
}

/**
 * Context size for OpenAI web search results.
 * Controls how much context window space is used for search results.
 */
enum class WebSearchContextSize(val value: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high")
}

/**
 * User location for localized search results.
 * Used by both OpenAI and Anthropic native web search.
 */
data class UserLocation(
    /** City name (e.g., "San Francisco") */
    val city: String? = null,
    /** ISO 3166-1 alpha-2 country code (e.g., "US") */
    val country: String? = null,
    /** Region/State name (e.g., "California") */
    val region: String? = null,
    /** IANA timezone (e.g., "America/Los_Angeles") */
    val timezone: String? = null
)

/**
 * Configuration for OpenAI native web search.
 * 
 * When searchApi is set to SearchAPI.OPENAI, this configuration controls
 * how the OpenAI web_search_preview tool behaves.
 * 
 * @property searchContextSize Amount of context to use for search results (low/medium/high)
 * @property userLocation Optional user location for localized results
 */
data class OpenAIWebSearchConfig(
    val searchContextSize: WebSearchContextSize = WebSearchContextSize.MEDIUM,
    val userLocation: UserLocation? = null
)

/**
 * Configuration for Anthropic native web search (web_search_20250305).
 * 
 * When searchApi is set to SearchAPI.ANTHROPIC, this configuration controls
 * how Anthropic's built-in web search tool behaves.
 * 
 * @property maxUses Maximum number of web searches per response (default: 5)
 * @property allowedDomains Optional list of domains to restrict searches to
 * @property blockedDomains Optional list of domains to exclude from searches
 * @property userLocation Optional user location for localized results
 */
data class AnthropicWebSearchConfig(
    val maxUses: Int = 5,
    val allowedDomains: List<String>? = null,
    val blockedDomains: List<String>? = null,
    val userLocation: UserLocation? = null
)

/**
 * Transport type for MCP server connections.
 */
enum class MCPTransport {
    /** Server-Sent Events over HTTP */
    SSE,
    /** Standard I/O with subprocess */
    STDIO
}

/**
 * Configuration for Model Context Protocol (MCP) servers.
 * Allows integration with external tools via MCP.
 * 
 * Mirrors Python: mcp_config in Configuration.
 */
data class MCPConfig(
    /** URL for SSE transport (e.g., "http://localhost:8931/sse") */
    val url: String? = null,
    /** Command and args for STDIO transport (e.g., ["npx", "@modelcontextprotocol/server-google-maps"]) */
    val command: List<String>? = null,
    /** Environment variables for STDIO subprocess */
    val environment: Map<String, String> = emptyMap(),
    /** List of tool names to include (null = include all) */
    val tools: List<String>? = null,
    /** Whether authentication is required */
    val authRequired: Boolean = false,
    /** Transport type to use */
    val transport: MCPTransport = MCPTransport.SSE
) {
    companion object {
        /**
         * Create SSE-based MCP config.
         */
        fun sse(url: String, tools: List<String>? = null) = MCPConfig(
            url = url,
            transport = MCPTransport.SSE,
            tools = tools
        )
        
        /**
         * Create STDIO-based MCP config for running MCP server as subprocess.
         */
        fun stdio(
            command: List<String>,
            environment: Map<String, String> = emptyMap(),
            tools: List<String>? = null
        ) = MCPConfig(
            command = command,
            environment = environment,
            transport = MCPTransport.STDIO,
            tools = tools
        )
    }
}

/**
 * Main configuration for the Deep Research agent.
 * 
 * This is a 1:1 port of the Python Open Deep Research Configuration class.
 * 
 * Configuration controls:
 * - Model selection for different phases (clarify, supervisor, researcher, compression, final report)
 * - Search API configuration (Tavily, OpenAI native, Anthropic native)
 * - Iteration limits for supervisor and researcher loops
 * - Concurrency settings
 * - Content length and token limits
 * 
 * Example usage:
 * ```kotlin
 * val config = DeepResearchConfig(
 *     openaiApiKey = "sk-...",
 *     tavilyApiKey = "tvly-...",
 *     maxResearchLoops = 5,
 *     maxReactToolCalls = 20
 * )
 * ```
 */
data class DeepResearchConfig(
    // ==================== API Keys ====================
    
    /**
     * OpenAI API key for GPT models.
     */
    val openaiApiKey: String? = System.getenv("OPENAI_API_KEY"),
    
    /**
     * Anthropic API key for Claude models.
     */
    val anthropicApiKey: String? = System.getenv("ANTHROPIC_API_KEY"),
    
    /**
     * Google/Gemini API key.
     */
    val googleApiKey: String? = System.getenv("GOOGLE_API_KEY"),
    
    /**
     * Tavily API key for web search.
     * Get one at: https://tavily.com
     */
    val tavilyApiKey: String? = System.getenv("TAVILY_API_KEY"),
    
    // ==================== Model Configuration ====================
    // Mirrors Python: clarify_model, supervisor_model, research_model, compression_model, final_report_model
    
    /**
     * Model for the clarify_with_user and write_research_brief nodes.
     * Should be fast and capable of understanding user intent.
     * Default: gpt-4o-mini
     */
    val clarifyModel: LLModel = OpenAIModels.Chat.GPT4oMini,
    
    /**
     * Model for the supervisor node that coordinates research.
     * Should be capable of strategic planning and delegation.
     * Default: gpt-4o
     */
    val supervisorModel: LLModel = OpenAIModels.Chat.GPT4o,
    
    /**
     * Model for individual researcher agents.
     * Handles web search and information synthesis.
     * Default: gpt-4o-mini (cost-effective for many searches)
     */
    val researchModel: LLModel = OpenAIModels.Chat.GPT4oMini,
    
    /**
     * Model for compressing research findings.
     * Should be good at summarization.
     * Default: gpt-4o-mini
     */
    val compressionModel: LLModel = OpenAIModels.Chat.GPT4oMini,
    
    /**
     * Model for generating the final report.
     * Should be capable of long-form writing.
     * Default: gpt-4o
     */
    val finalReportModel: LLModel = OpenAIModels.Chat.GPT4o,
    
    /**
     * Model for summarizing long webpage content from search results.
     * Used when raw_content exceeds maxContentLength.
     * Mirrors Python: summarization_model (default: openai:gpt-4.1-mini)
     */
    val summarizationModel: LLModel = OpenAIModels.Chat.GPT4oMini,
    
    // ==================== Model Max Tokens ====================
    // Mirrors Python: *_model_max_tokens settings
    // If null, defaults are derived from the model's maxOutputTokens property
    
    /**
     * Maximum output tokens for the summarization model.
     * If null, uses summarizationModel.maxOutputTokens.
     * Mirrors Python: summarization_model_max_tokens
     */
    val summarizationModelMaxTokens: Int? = null,
    
    /**
     * Maximum output tokens for the research model.
     * If null, uses researchModel.maxOutputTokens.
     * Mirrors Python: research_model_max_tokens
     */
    val researchModelMaxTokens: Int? = null,
    
    /**
     * Maximum output tokens for the compression model.
     * If null, uses compressionModel.maxOutputTokens.
     * Mirrors Python: compression_model_max_tokens
     */
    val compressionModelMaxTokens: Int? = null,
    
    /**
     * Maximum output tokens for the final report model.
     * If null, uses finalReportModel.maxOutputTokens.
     * Mirrors Python: final_report_model_max_tokens
     */
    val finalReportModelMaxTokens: Int? = null,
    
    // ==================== Research Limits ====================
    // Mirrors Python: max_research_loops, max_react_tool_calls
    
    /**
     * Maximum number of research loops for the Research Supervisor.
     * This is the number of times the supervisor will delegate and reflect.
     * Higher values allow more thorough research but take longer.
     * Mirrors Python: max_research_loops (default: 5)
     */
    val maxResearchLoops: Int = 5,
    
    /**
     * Maximum number of tool calling iterations in a single researcher step.
     * Controls how many search queries a researcher can make per task.
     * Mirrors Python: max_react_tool_calls (default: 20)
     */
    val maxReactToolCalls: Int = 20,
    
    /**
     * Maximum iterations for the supervisor subgraph before forcing completion.
     */
    val maxSupervisorIterations: Int = 10,
    
    /**
     * Maximum number of retries for structured output calls.
     */
    val maxStructuredOutputRetries: Int = 3,
    
    /**
     * Maximum number of research units to run concurrently.
     * Higher values speed up research but may hit rate limits.
     */
    val maxConcurrentResearchUnits: Int = 5,
    
    // ==================== LLM Parameters ====================
    
    /**
     * Temperature for LLM calls in research tasks.
     * 0.0 = deterministic, higher = more creative.
     * Default is 0.0 for consistent, factual research.
     */
    val temperature: Double = 0.0,
    
    // ==================== Timeout Configuration ====================
    
    /**
     * HTTP request timeout in milliseconds for external API calls (e.g., Tavily).
     */
    val httpRequestTimeoutMs: Long = 60_000,
    
    /**
     * HTTP connection timeout in milliseconds for external API calls.
     */
    val httpConnectTimeoutMs: Long = 10_000,
    
    /**
     * Timeout in milliseconds for summarization operations.
     */
    val summarizationTimeoutMs: Long = 60_000,
    
    // ==================== Search Configuration ====================
    // Mirrors Python: search_api, tavily_search config
    
    /**
     * Search API to use for research.
     * TAVILY is recommended for best results.
     */
    val searchApi: SearchAPI = SearchAPI.TAVILY,
    
    /**
     * Maximum number of search results to return per Tavily query.
     * Mirrors Python: TAVILY_SEARCH.max_results
     */
    val tavilySearchMaxResults: Int = 5,
    
    /**
     * Whether to include raw content from Tavily results.
     * Mirrors Python: TAVILY_SEARCH.include_raw_content
     */
    val tavilyIncludeRawContent: Boolean = true,
    
    /**
     * Search depth for Tavily ("basic" or "advanced").
     * "advanced" provides more thorough results.
     * Mirrors Python: TAVILY_SEARCH.search_depth
     */
    val tavilySearchDepth: String = "advanced",
    
    // ==================== Native Search Configuration ====================
    
    /**
     * OpenAI native web search configuration.
     * Used when searchApi is set to SearchAPI.OPENAI.
     * 
     * Requires a model that supports web search (e.g., gpt-4o, gpt-4o-mini).
     * The model will use OpenAI's built-in web_search_preview tool.
     */
    val openaiWebSearch: OpenAIWebSearchConfig = OpenAIWebSearchConfig(),
    
    /**
     * Anthropic native web search configuration.
     * Used when searchApi is set to SearchAPI.ANTHROPIC.
     * 
     * Requires a Claude model that supports web search.
     * The model will use Anthropic's built-in web_search_20250305 tool.
     */
    val anthropicWebSearch: AnthropicWebSearchConfig = AnthropicWebSearchConfig(),
    
    // ==================== Feature Flags ====================
    
    /**
     * Whether to allow the agent to ask clarifying questions.
     * If false, goes directly to research without clarification.
     * Mirrors Python: allow_clarification
     */
    val enableClarification: Boolean = true,
    
    /**
     * Maximum character length for webpage content before truncation.
     */
    val maxContentLength: Int = 50000,
    
    /**
     * Maximum character length for research notes when truncating for context window.
     * Used to prevent context overflow when building supervisor input.
     */
    val maxNoteLength: Int = 2000,
    
    /**
     * Maximum character length for fallback content truncation.
     * Used when summarization fails or times out.
     */
    val fallbackContentLength: Int = 10000,
    
    // ==================== MCP Configuration ====================
    
    /**
     * MCP server configuration for additional tools.
     */
    val mcpConfig: MCPConfig? = null,
    
    /**
     * Additional instructions for the agent regarding MCP tools.
     * This is injected into system prompts when MCP tools are available.
     */
    val mcpPrompt: String? = null
) {
    companion object {
        /**
         * Create a default configuration from environment variables.
         * 
         * Required environment variables:
         * - OPENAI_API_KEY (or ANTHROPIC_API_KEY, GOOGLE_API_KEY)
         * - TAVILY_API_KEY (for web search)
         */
        fun fromEnvironment(): DeepResearchConfig = DeepResearchConfig()
        
        /**
         * Create a minimal configuration for testing.
         * Uses reduced iteration limits for faster test execution.
         */
        fun forTesting(): DeepResearchConfig = DeepResearchConfig(
            maxResearchLoops = 2,
            maxReactToolCalls = 3,
            maxSupervisorIterations = 3,
            maxConcurrentResearchUnits = 2,
            enableClarification = false
        )
        
        /**
         * Create a configuration optimized for speed (less thorough).
         */
        fun fast(): DeepResearchConfig = DeepResearchConfig(
            maxResearchLoops = 2,
            maxReactToolCalls = 5,
            maxSupervisorIterations = 5,
            enableClarification = false
        )
        
        /**
         * Create a configuration optimized for thoroughness (slower).
         */
        fun thorough(): DeepResearchConfig = DeepResearchConfig(
            maxResearchLoops = 10,
            maxReactToolCalls = 30,
            maxSupervisorIterations = 20,
            tavilySearchMaxResults = 10,
            enableClarification = true
        )
        
        /**
         * Default max output tokens when model doesn't specify.
         * Used as fallback for older or custom models.
         */
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 4096
    }
    
    // ==================== Effective Max Tokens Accessors ====================
    // These use the model's maxOutputTokens when config value is null
    
    /**
     * Get effective max tokens for summarization model.
     * Uses configured value or falls back to model's maxOutputTokens.
     */
    fun getEffectiveSummarizationMaxTokens(): Int =
        summarizationModelMaxTokens ?: summarizationModel.maxOutputTokens?.toInt() ?: DEFAULT_MAX_OUTPUT_TOKENS
    
    /**
     * Get effective max tokens for research model.
     * Uses configured value or falls back to model's maxOutputTokens.
     */
    fun getEffectiveResearchMaxTokens(): Int =
        researchModelMaxTokens ?: researchModel.maxOutputTokens?.toInt() ?: DEFAULT_MAX_OUTPUT_TOKENS
    
    /**
     * Get effective max tokens for compression model.
     * Uses configured value or falls back to model's maxOutputTokens.
     */
    fun getEffectiveCompressionMaxTokens(): Int =
        compressionModelMaxTokens ?: compressionModel.maxOutputTokens?.toInt() ?: DEFAULT_MAX_OUTPUT_TOKENS
    
    /**
     * Get effective max tokens for final report model.
     * Uses configured value or falls back to model's maxOutputTokens.
     */
    fun getEffectiveFinalReportMaxTokens(): Int =
        finalReportModelMaxTokens ?: finalReportModel.maxOutputTokens?.toInt() ?: DEFAULT_MAX_OUTPUT_TOKENS
}
