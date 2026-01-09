package ai.koog.deepresearch.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.deepresearch.config.DeepResearchConfig
import ai.koog.deepresearch.model.TavilyResult
import ai.koog.deepresearch.model.TavilySearchResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Search tools for conducting web research.
 * 
 * Provides integration with:
 * - Tavily Search API (primary)
 * - Placeholder for other search APIs (OpenAI, Anthropic native search)
 */
@LLMDescription("Tools for conducting web searches and gathering research information")
class SearchTools(
    private val config: DeepResearchConfig
) : ToolSet {
    
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }
    
    @Serializable
    private data class TavilySearchRequest(
        val api_key: String,
        val query: String,
        val max_results: Int = 5,
        val include_raw_content: Boolean = true,
        val search_depth: String = "advanced",
        val topic: String = "general"
    )
    
    @Serializable
    private data class TavilyApiResponse(
        val query: String,
        val results: List<TavilyApiResult>
    )
    
    @Serializable
    private data class TavilyApiResult(
        val title: String,
        val url: String,
        val content: String,
        val raw_content: String? = null,
        val score: Double? = null
    )
    
    /**
     * Execute a Tavily search with the given query.
     * 
     * This tool searches the web using the Tavily API and returns comprehensive results
     * including page content when available.
     * 
     * @param query The search query to execute
     * @return Formatted string containing search results with titles, URLs, and content
     */
    @Tool
    @LLMDescription("""
        Search the web using Tavily API for comprehensive, accurate results.
        Use this tool to find current information, facts, news, and research data.
        
        Best practices:
        - Use clear, specific search queries
        - One query per call for best results
        - Start with broader queries, then narrow down
        
        Returns formatted results with titles, URLs, and content snippets.
    """)
    suspend fun tavilySearch(
        @LLMDescription("The search query to execute. Should be clear and specific.")
        query: String
    ): String {
        val apiKey = config.tavilyApiKey 
            ?: return "Error: TAVILY_API_KEY not configured. Please set the environment variable."
        
        return try {
            val result = executeTavilySearch(apiKey, query)
            if (result != null) {
                formatSearchResults(listOf(result))
            } else {
                "No search results found for query: $query"
            }
        } catch (e: Exception) {
            "Error executing search: ${e.message}"
        }
    }
    
    private suspend fun executeTavilySearch(apiKey: String, query: String): TavilySearchResponse? {
        return try {
            val response = httpClient.post("https://api.tavily.com/search") {
                contentType(ContentType.Application.Json)
                setBody(TavilySearchRequest(
                    api_key = apiKey,
                    query = query,
                    max_results = config.tavilySearchMaxResults,
                    include_raw_content = true
                ))
            }
            
            val apiResponse = response.body<TavilyApiResponse>()
            TavilySearchResponse(
                query = apiResponse.query,
                results = apiResponse.results.map { result ->
                    TavilyResult(
                        title = result.title,
                        url = result.url,
                        content = result.content,
                        rawContent = result.raw_content,
                        score = result.score
                    )
                }
            )
        } catch (e: Exception) {
            null
        }
    }
    
    private fun formatSearchResults(responses: List<TavilySearchResponse>): String {
        if (responses.isEmpty()) {
            return "No search results found."
        }
        
        val sb = StringBuilder()
        
        responses.forEach { response ->
            sb.appendLine("## Search Query: ${response.query}")
            sb.appendLine()
            
            response.results.forEachIndexed { index, result ->
                sb.appendLine("### Result ${index + 1}: ${result.title}")
                sb.appendLine("**URL**: ${result.url}")
                sb.appendLine()
                sb.appendLine("**Summary**: ${result.content}")
                
                // Include raw content if available (truncated for readability)
                result.rawContent?.let { raw ->
                    val truncated = if (raw.length > config.maxContentLength) {
                        raw.take(config.maxContentLength) + "... [truncated]"
                    } else {
                        raw
                    }
                    sb.appendLine()
                    sb.appendLine("**Full Content**:")
                    sb.appendLine(truncated)
                }
                
                sb.appendLine()
                sb.appendLine("---")
                sb.appendLine()
            }
        }
        
        return sb.toString()
    }
    
    /**
     * Close the HTTP client when done.
     */
    fun close() {
        httpClient.close()
    }
}
