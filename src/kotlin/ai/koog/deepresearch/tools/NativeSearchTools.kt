@file:OptIn(ai.koog.prompt.executor.clients.InternalLLMClientApi::class)

package ai.koog.deepresearch.tools

import ai.koog.deepresearch.config.*
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIUserLocation
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIWebSearchOptions
import ai.koog.prompt.params.LLMParams

/**
 * Utility for configuring native web search tools for OpenAI and Anthropic.
 * 
 * Native search tools are built-in capabilities of LLM providers that allow
 * the model to search the web directly without needing external tool implementations.
 * 
 * Supported providers:
 * - **OpenAI**: Uses `web_search_preview` tool via Responses API
 * - **Anthropic**: Uses `web_search_20250305` server-side tool
 * 
 * Example usage:
 * ```kotlin
 * val config = DeepResearchConfig(
 *     searchApi = SearchAPI.OPENAI,
 *     openaiWebSearch = OpenAIWebSearchConfig(
 *         searchContextSize = WebSearchContextSize.HIGH,
 *         userLocation = UserLocation(city = "San Francisco", country = "US")
 *     )
 * )
 * 
 * val params = NativeSearchTools.createOpenAIParams(config)
 * // Use params when calling the LLM
 * ```
 */
object NativeSearchTools {
    
    /**
     * Create OpenAI LLM params with web search enabled.
     * 
     * @param config Deep Research configuration with OpenAI web search settings
     * @param baseParams Optional base params to merge with (web search will be added)
     * @return OpenAIChatParams configured for web search
     */
    fun createOpenAIParams(
        config: DeepResearchConfig,
        baseParams: LLMParams? = null
    ): OpenAIChatParams {
        val webSearchConfig = config.openaiWebSearch
        
        val userLocation = webSearchConfig.userLocation?.let { loc ->
            OpenAIUserLocation(
                approximate = OpenAIUserLocation.ApproximateLocation(
                    city = loc.city,
                    country = loc.country,
                    region = loc.region,
                    timezone = loc.timezone
                )
            )
        }
        
        val webSearchOptions = OpenAIWebSearchOptions(
            searchContextSize = webSearchConfig.searchContextSize.value,
            userLocation = userLocation
        )
        
        return OpenAIChatParams(
            temperature = baseParams?.temperature,
            maxTokens = baseParams?.maxTokens,
            numberOfChoices = baseParams?.numberOfChoices,
            speculation = baseParams?.speculation,
            schema = baseParams?.schema,
            toolChoice = baseParams?.toolChoice,
            user = baseParams?.user,
            additionalProperties = baseParams?.additionalProperties,
            webSearchOptions = webSearchOptions
        )
    }
    
    /**
     * Create Anthropic LLM params with web search enabled.
     * 
     * @param config Deep Research configuration with Anthropic web search settings
     * @param baseParams Optional base params to merge with (web search will be added)
     * @return AnthropicParams configured for web search
     * 
     * Note: Anthropic web search tools are not yet available in this version.
     * This returns basic AnthropicParams without web search for now.
     */
    fun createAnthropicParams(
        config: DeepResearchConfig,
        baseParams: LLMParams? = null
    ): AnthropicParams {
        // If baseParams is AnthropicParams, preserve its settings
        val anthropicBase = baseParams as? AnthropicParams
        
        // TODO: Add web search support when AnthropicWebSearchTool becomes available
        // val webSearchConfig = config.anthropicWebSearch
        // For now, return params without native web search
        
        return AnthropicParams(
            temperature = baseParams?.temperature,
            maxTokens = baseParams?.maxTokens,
            numberOfChoices = baseParams?.numberOfChoices,
            speculation = baseParams?.speculation,
            schema = baseParams?.schema,
            toolChoice = baseParams?.toolChoice,
            user = baseParams?.user,
            additionalProperties = baseParams?.additionalProperties,
            topP = anthropicBase?.topP,
            topK = anthropicBase?.topK,
            stopSequences = anthropicBase?.stopSequences,
            container = anthropicBase?.container,
            mcpServers = anthropicBase?.mcpServers,
            serviceTier = anthropicBase?.serviceTier,
            thinking = anthropicBase?.thinking
        )
    }
    
    /**
     * Create LLM params based on the configured search API.
     * 
     * This is the main entry point for getting native search params.
     * Returns null if native search is not configured (Tavily, Google, or None).
     * 
     * @param config Deep Research configuration
     * @param baseParams Optional base params to merge with
     * @return LLMParams configured for native search, or null if not applicable
     */
    fun createNativeSearchParams(
        config: DeepResearchConfig,
        baseParams: LLMParams? = null
    ): LLMParams? {
        return when (config.searchApi) {
            SearchAPI.OPENAI -> createOpenAIParams(config, baseParams)
            SearchAPI.ANTHROPIC -> createAnthropicParams(config, baseParams)
            SearchAPI.TAVILY, SearchAPI.GOOGLE, SearchAPI.NONE -> null
        }
    }
    
    /**
     * Check if native search is enabled in the configuration.
     */
    fun isNativeSearchEnabled(config: DeepResearchConfig): Boolean {
        return config.searchApi == SearchAPI.OPENAI || config.searchApi == SearchAPI.ANTHROPIC
    }
    
    /**
     * Get a description of the native search configuration for logging/debugging.
     */
    fun describeConfig(config: DeepResearchConfig): String {
        return when (config.searchApi) {
            SearchAPI.OPENAI -> {
                val ws = config.openaiWebSearch
                buildString {
                    append("OpenAI Native Web Search (web_search_preview)")
                    append("\n  - Context Size: ${ws.searchContextSize.value}")
                    ws.userLocation?.let { loc ->
                        append("\n  - User Location: ${loc.city ?: ""}${loc.region?.let { ", $it" } ?: ""}${loc.country?.let { " ($it)" } ?: ""}")
                    }
                }
            }
            SearchAPI.ANTHROPIC -> {
                val ws = config.anthropicWebSearch
                buildString {
                    append("Anthropic Native Web Search (web_search_20250305)")
                    append("\n  - Max Uses: ${ws.maxUses}")
                    ws.allowedDomains?.let { append("\n  - Allowed Domains: ${it.joinToString(", ")}") }
                    ws.blockedDomains?.let { append("\n  - Blocked Domains: ${it.joinToString(", ")}") }
                    ws.userLocation?.let { loc ->
                        append("\n  - User Location: ${loc.city ?: ""}${loc.region?.let { ", $it" } ?: ""}${loc.country?.let { " ($it)" } ?: ""}")
                    }
                }
            }
            SearchAPI.TAVILY -> "Tavily Search API"
            SearchAPI.GOOGLE -> "Google Search API"
            SearchAPI.NONE -> "No search configured"
        }
    }
}
