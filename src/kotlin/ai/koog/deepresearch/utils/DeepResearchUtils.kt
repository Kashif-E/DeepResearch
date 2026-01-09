package ai.koog.deepresearch.utils

import ai.koog.deepresearch.config.DeepResearchConfig
import ai.koog.deepresearch.config.SearchAPI
import ai.koog.deepresearch.model.ResearchSummary
import ai.koog.deepresearch.state.Message
import ai.koog.deepresearch.state.Summary
import ai.koog.deepresearch.prompts.ResearchPrompts
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.structure.executeStructured
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Utility functions that mirror the Python utils.py implementation.
 * Provides search, summarization, token limit handling, and misc utilities.
 */

// ==================== Tavily Search Utils ====================

@Serializable
data class TavilySearchRequest(
    val api_key: String,
    val query: String,
    val max_results: Int = 5,
    val include_raw_content: Boolean = true,
    val search_depth: String = "advanced",
    val topic: String = "general"
)

@Serializable
data class TavilySearchResponse(
    val query: String,
    val results: List<TavilySearchResult>
)

@Serializable
data class TavilySearchResult(
    val title: String,
    val url: String,
    val content: String,
    val raw_content: String? = null,
    val score: Double? = null
)

/**
 * HTTP client factory for API calls.
 * Creates clients with configurable timeouts.
 */
object HttpClientProvider {
    /**
     * Default client with standard timeouts.
     * Use createClient() for config-based timeouts.
     */
    val client: HttpClient by lazy {
        createClient()
    }
    
    /**
     * Create an HTTP client with custom timeouts from config.
     */
    fun createClient(
        requestTimeoutMs: Long = 60_000,
        connectTimeoutMs: Long = 10_000
    ): HttpClient {
        return HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = requestTimeoutMs
                connectTimeoutMillis = connectTimeoutMs
            }
        }
    }
}

/**
 * Execute Tavily search with multiple queries in parallel.
 * Mirrors Python tavily_search_async function.
 */
suspend fun tavilySearchAsync(
    searchQueries: List<String>,
    config: DeepResearchConfig,
    maxResults: Int = 5,
    topic: String = "general",
    includeRawContent: Boolean = true
): List<TavilySearchResponse> {
    val apiKey = config.tavilyApiKey ?: return emptyList()
    
    // Create client with config-based timeouts
    val client = HttpClientProvider.createClient(
        requestTimeoutMs = config.httpRequestTimeoutMs,
        connectTimeoutMs = config.httpConnectTimeoutMs
    )
    
    return coroutineScope {
        searchQueries.map { query ->
            async {
                try {
                    val response = client.post("https://api.tavily.com/search") {
                        contentType(ContentType.Application.Json)
                        setBody(TavilySearchRequest(
                            api_key = apiKey,
                            query = query,
                            max_results = maxResults,
                            include_raw_content = includeRawContent,
                            search_depth = "advanced",
                            topic = topic
                        ))
                    }
                    response.body<TavilySearchResponse>()
                } catch (e: Exception) {
                    TavilySearchResponse(query = query, results = emptyList())
                }
            }
        }.awaitAll()
    }
}

// ==================== Webpage Summarization ====================

/**
 * Data class for webpage summarization results.
 */
@Serializable
data class WebpageSummary(
    val summary: String,
    val keyExcerpts: String? = null
)

/**
 * Default fallback content length when summarization fails.
 * Can be overridden via DeepResearchConfig.fallbackContentLength.
 */
private const val DEFAULT_FALLBACK_CONTENT_LENGTH = 10000

/**
 * Summarize webpage content using AI model with timeout protection.
 * Mirrors Python summarize_webpage function.
 *
 * @param executor The prompt executor for LLM calls
 * @param model The model to use for summarization
 * @param webpageContent Raw webpage content to be summarized
 * @param timeoutMs Timeout in milliseconds (default: 60 seconds)
 * @param fallbackContentLength Max length for truncated content on failure
 * @return Formatted summary with key excerpts, or original content if summarization fails
 */
suspend fun summarizeWebpage(
    executor: PromptExecutor,
    model: LLModel,
    webpageContent: String,
    timeoutMs: Long = 60_000,
    fallbackContentLength: Int = DEFAULT_FALLBACK_CONTENT_LENGTH
): String {
    return try {
        withTimeout(timeoutMs) {
            val summarizationPrompt = prompt("summarization") {
                user(ResearchPrompts.summarizeWebpagePrompt(webpageContent))
            }
            
            // Use Koog's executeStructured for type-safe JSON parsing
            val result = executor.executeStructured(
                prompt = summarizationPrompt,
                model = model,
                serializer = serializer<ResearchSummary>()
            )
            
            val summary = result.getOrThrow().data
            
            buildString {
                appendLine("<summary>")
                appendLine(summary.summary)
                appendLine("</summary>")
                if (summary.keyExcerpts.isNotBlank()) {
                    appendLine()
                    appendLine("<key_excerpts>")
                    appendLine(summary.keyExcerpts)
                    appendLine("</key_excerpts>")
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        // Return truncated original content on timeout
        webpageContent.take(fallbackContentLength) + "\n\n[Summary timed out - using truncated content]"
    } catch (e: Exception) {
        // Return truncated original content on error
        webpageContent.take(fallbackContentLength) + "\n\n[Summarization failed: ${e.message}]"
    }
}

/**
 * Execute Tavily search and format results.
 * Mirrors Python tavily_search tool function.
 * 
 * When summarizationExecutor is provided and content exceeds maxContentLength,
 * the content will be summarized rather than truncated.
 *
 * @param queries List of search queries to execute
 * @param config Deep research configuration
 * @param summarizationExecutor Optional executor for summarizing long content
 * @param summarizationModel Model to use for summarization (defaults to config.summarizationModel)
 * @param maxResults Maximum results per query
 * @param topic Topic category for filtering
 */
suspend fun tavilySearch(
    queries: List<String>,
    config: DeepResearchConfig,
    summarizationExecutor: PromptExecutor? = null,
    summarizationModel: LLModel? = null,
    maxResults: Int = 5,
    topic: String = "general"
): String {
    // Step 1: Execute search queries asynchronously
    val searchResults = tavilySearchAsync(
        searchQueries = queries,
        config = config,
        maxResults = maxResults,
        topic = topic,
        includeRawContent = true
    )
    
    // Step 2: Deduplicate results by URL
    val uniqueResults = mutableMapOf<String, Pair<TavilySearchResult, String>>()
    for (response in searchResults) {
        for (result in response.results) {
            if (result.url !in uniqueResults) {
                uniqueResults[result.url] = Pair(result, response.query)
            }
        }
    }
    
    // Step 3: Summarize long content in parallel (if executor provided)
    val model = summarizationModel ?: config.summarizationModel
    val processedResults = if (summarizationExecutor != null) {
        coroutineScope {
            uniqueResults.mapValues { (_, pair) ->
                val (result, query) = pair
                val rawContent = result.raw_content
                
                // Check if content needs summarization
                if (!rawContent.isNullOrBlank() && rawContent.length > config.maxContentLength) {
                    async {
                        val summary = summarizeWebpage(
                            executor = summarizationExecutor,
                            model = model,
                            webpageContent = rawContent.take(config.maxContentLength),
                            fallbackContentLength = config.fallbackContentLength
                        )
                        Pair(result.copy(raw_content = summary), query)
                    }
                } else {
                    async { pair }
                }
            }.mapValues { it.value.await() }
        }
    } else {
        uniqueResults
    }
    
    // Step 4: Format the output
    if (processedResults.isEmpty()) {
        return "No valid search results found. Please try different search queries or use a different search API."
    }
    
    val sb = StringBuilder("Search results: \n\n")
    processedResults.entries.forEachIndexed { index, (url, pair) ->
        val (result, query) = pair
        sb.appendLine("\n--- SOURCE ${index + 1}: ${result.title} ---")
        sb.appendLine("URL: $url")
        sb.appendLine()
        
        // Use raw content if available and within limit
        val content = if (!result.raw_content.isNullOrBlank()) {
            val truncated = result.raw_content.take(config.maxContentLength)
            if (result.raw_content.length > config.maxContentLength) {
                "$truncated... [truncated]"
            } else {
                truncated
            }
        } else {
            result.content
        }
        
        sb.appendLine("CONTENT:")
        sb.appendLine(content)
        sb.appendLine()
        sb.appendLine("-".repeat(80))
    }
    
    return sb.toString()
}

// ==================== Think Tool ====================

/**
 * Think tool for strategic reflection.
 * Mirrors Python think_tool function.
 */
fun thinkTool(reflection: String): String {
    return "Reflection recorded: $reflection"
}

// ==================== Token Limit Handling ====================

/**
 * Get token/context limit for an LLModel.
 * Uses Koog's native model definition - no hardcoded values.
 * 
 * @param model The LLModel to get the context length for
 * @return The context length as Long
 */
fun getModelTokenLimit(model: LLModel): Long = model.contextLength

/**
 * Check if an exception indicates token limit exceeded.
 * Uses Koog's LLMProvider to determine provider-specific checks.
 * 
 * @param exception The exception to check
 * @param model Optional LLModel to determine provider-specific checks
 * @return true if the exception indicates a token limit was exceeded
 */
fun isTokenLimitExceeded(exception: Exception, model: LLModel? = null): Boolean {
    val errorStr = exception.message?.lowercase() ?: return false
    val exceptionClassName = exception::class.simpleName ?: ""
    val exceptionTypeName = exception::class.qualifiedName?.lowercase() ?: ""
    
    // Use Koog's LLMProvider for provider-specific checks
    return when (model?.provider) {
        LLMProvider.OpenAI -> checkOpenAITokenLimit(errorStr, exceptionClassName, exceptionTypeName)
        LLMProvider.Anthropic -> checkAnthropicTokenLimit(errorStr, exceptionClassName, exceptionTypeName)
        LLMProvider.Google -> checkGoogleTokenLimit(errorStr, exceptionClassName, exceptionTypeName)
        LLMProvider.Bedrock -> checkBedrockTokenLimit(errorStr, exceptionClassName, exceptionTypeName)
        LLMProvider.MistralAI -> checkGenericTokenLimit(errorStr)
        LLMProvider.Ollama -> checkGenericTokenLimit(errorStr)
        LLMProvider.OpenRouter -> checkGenericTokenLimit(errorStr)
        LLMProvider.DeepSeek -> checkGenericTokenLimit(errorStr)
        else -> {
            // Fallback: check all major providers
            checkOpenAITokenLimit(errorStr, exceptionClassName, exceptionTypeName) ||
            checkAnthropicTokenLimit(errorStr, exceptionClassName, exceptionTypeName) ||
            checkGoogleTokenLimit(errorStr, exceptionClassName, exceptionTypeName) ||
            checkGenericTokenLimit(errorStr)
        }
    }
}

/**
 * Check if an exception indicates token limit exceeded (string-based overload).
 * For backward compatibility when only model ID string is available.
 * 
 * @param exception The exception to check
 * @param modelId Optional model ID string to infer provider
 * @return true if the exception indicates a token limit was exceeded
 */
fun isTokenLimitExceeded(exception: Exception, modelId: String?): Boolean {
    val errorStr = exception.message?.lowercase() ?: return false
    val exceptionClassName = exception::class.simpleName ?: ""
    val exceptionTypeName = exception::class.qualifiedName?.lowercase() ?: ""
    
    // Infer provider from model ID using Koog's provider IDs
    val provider = modelId?.lowercase()?.let { id ->
        when {
            id.startsWith(LLMProvider.OpenAI.id) || "gpt" in id || id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") -> LLMProvider.OpenAI
            id.startsWith(LLMProvider.Anthropic.id) || "claude" in id -> LLMProvider.Anthropic
            id.startsWith(LLMProvider.Google.id) || "gemini" in id -> LLMProvider.Google
            id.startsWith(LLMProvider.Bedrock.id) -> LLMProvider.Bedrock
            id.startsWith(LLMProvider.MistralAI.id) || "mistral" in id -> LLMProvider.MistralAI
            id.startsWith(LLMProvider.Ollama.id) -> LLMProvider.Ollama
            id.startsWith(LLMProvider.DeepSeek.id) || "deepseek" in id -> LLMProvider.DeepSeek
            else -> null
        }
    }
    
    return when (provider) {
        LLMProvider.OpenAI -> checkOpenAITokenLimit(errorStr, exceptionClassName, exceptionTypeName)
        LLMProvider.Anthropic -> checkAnthropicTokenLimit(errorStr, exceptionClassName, exceptionTypeName)
        LLMProvider.Google -> checkGoogleTokenLimit(errorStr, exceptionClassName, exceptionTypeName)
        LLMProvider.Bedrock -> checkBedrockTokenLimit(errorStr, exceptionClassName, exceptionTypeName)
        else -> {
            checkOpenAITokenLimit(errorStr, exceptionClassName, exceptionTypeName) ||
            checkAnthropicTokenLimit(errorStr, exceptionClassName, exceptionTypeName) ||
            checkGoogleTokenLimit(errorStr, exceptionClassName, exceptionTypeName) ||
            checkGenericTokenLimit(errorStr)
        }
    }
}

/**
 * Check if exception indicates OpenAI token limit exceeded.
 */
private fun checkOpenAITokenLimit(
    errorStr: String,
    className: String,
    typeName: String
): Boolean {
    // Check if this is an OpenAI exception
    val isOpenAIException = LLMProvider.OpenAI.id in typeName
    
    // Check for typical OpenAI token limit error types
    val isRequestError = className in listOf("BadRequestError", "InvalidRequestError")
    
    if (isOpenAIException && isRequestError) {
        val tokenKeywords = listOf("token", "context", "length", "maximum context", "reduce")
        if (tokenKeywords.any { it in errorStr }) {
            return true
        }
    }
    
    // Check for specific OpenAI error codes/types in message
    if ("context_length_exceeded" in errorStr || "invalid_request_error" in errorStr) {
        return true
    }
    
    return false
}

/**
 * Check if exception indicates Anthropic token limit exceeded.
 */
private fun checkAnthropicTokenLimit(
    errorStr: String,
    className: String,
    typeName: String
): Boolean {
    val isAnthropicException = LLMProvider.Anthropic.id in typeName
    val isBadRequest = className == "BadRequestError"
    
    if (isAnthropicException && isBadRequest) {
        if ("prompt is too long" in errorStr) {
            return true
        }
    }
    
    if ("prompt is too long" in errorStr && isAnthropicException) {
        return true
    }
    
    return false
}

/**
 * Check if exception indicates Google/Gemini token limit exceeded.
 */
private fun checkGoogleTokenLimit(
    errorStr: String,
    className: String,
    typeName: String
): Boolean {
    val isGoogleException = LLMProvider.Google.id in typeName
    val isResourceExhausted = className in listOf("ResourceExhausted", "GoogleGenerativeAIFetchError")
    
    if (isGoogleException && isResourceExhausted) {
        return true
    }
    
    if ("google.api_core.exceptions.resourceexhausted" in typeName) {
        return true
    }
    
    if (isGoogleException && ("resource" in errorStr && "exhausted" in errorStr)) {
        return true
    }
    
    return false
}

/**
 * Check if exception indicates AWS Bedrock token limit exceeded.
 */
private fun checkBedrockTokenLimit(
    errorStr: String,
    className: String,
    typeName: String
): Boolean {
    val isBedrockException = LLMProvider.Bedrock.id in typeName || "bedrock" in typeName
    
    if (isBedrockException) {
        val tokenKeywords = listOf("token", "context", "length", "too long", "exceeds", "limit")
        if (tokenKeywords.any { it in errorStr }) {
            return true
        }
    }
    
    // Bedrock often wraps Anthropic/other provider errors
    if ("validationexception" in typeName && "token" in errorStr) {
        return true
    }
    
    return false
}

/**
 * Generic fallback check for token limit errors.
 */
private fun checkGenericTokenLimit(errorStr: String): Boolean {
    val tokenKeywords = listOf(
        "token", "context", "length", "maximum context", "reduce",
        "too long", "exceeds", "limit", "overflow"
    )
    return tokenKeywords.any { it in errorStr }
}

// ==================== Date Utils ====================

/**
 * Get today's date formatted for prompts.
 * Mirrors Python get_today_str function.
 */
fun getTodayStr(): String {
    val now = LocalDateTime.now()
    val formatter = DateTimeFormatter.ofPattern("EEE MMM d, yyyy", Locale.ENGLISH)
    return now.format(formatter)
}

// ==================== Search Tool Factory ====================

/**
 * Get search tools based on configured API.
 * Mirrors Python get_search_tool function.
 */
fun getSearchToolDescription(searchApi: SearchAPI): String {
    return when (searchApi) {
        SearchAPI.TAVILY -> """
            Use tavilySearch to search the web for information.
            - Accepts a list of search queries
            - Returns comprehensive results with URLs and content
            - Best for current events, facts, and research
        """.trimIndent()
        
        SearchAPI.OPENAI -> """
            Native OpenAI web search is available.
            The model will automatically search when needed.
        """.trimIndent()
        
        SearchAPI.ANTHROPIC -> """
            Native Anthropic web search is available.
            The model will automatically search when needed.
        """.trimIndent()
        
        SearchAPI.GOOGLE -> """
            Google search is available via web tools.
            Use googleSearch to search the web for information.
        """.trimIndent()
        
        SearchAPI.NONE -> """
            No web search is configured.
            Only MCP tools are available if configured.
        """.trimIndent()
    }
}

// ==================== Message Utilities ====================

/**
 * Convert internal messages to format suitable for LLM.
 */
fun messagesToPromptString(messages: List<Message>): String {
    return messages.joinToString("\n\n") { message ->
        when (message) {
            is Message.System -> "System: ${message.content}"
            is Message.Human -> "Human: ${message.content}"
            is Message.AI -> "Assistant: ${message.content}"
            is Message.Tool -> "Tool (${message.name}): ${message.content}"
        }
    }
}

// ==================== Native Web Search Detection ====================

/**
 * Check if OpenAI native web search was called.
 * Mirrors Python openai_websearch_called function.
 */
fun openaiWebsearchCalled(responseMetadata: Map<String, Any>?): Boolean {
    val toolOutputs = responseMetadata?.get("tool_outputs") as? List<*> ?: return false
    return toolOutputs.any { output ->
        (output as? Map<*, *>)?.get("type") == "web_search_call"
    }
}

/**
 * Check if Anthropic native web search was called.
 * Mirrors Python anthropic_websearch_called function.
 */
fun anthropicWebsearchCalled(responseMetadata: Map<String, Any>?): Boolean {
    val usage = responseMetadata?.get("usage") as? Map<*, *> ?: return false
    val serverToolUse = usage["server_tool_use"] as? Map<*, *> ?: return false
    val webSearchRequests = serverToolUse["web_search_requests"] as? Int ?: return false
    return webSearchRequests > 0
}
