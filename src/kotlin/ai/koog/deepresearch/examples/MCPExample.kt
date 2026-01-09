package ai.koog.deepresearch.examples

import ai.koog.deepresearch.DeepResearchAgent
import ai.koog.deepresearch.config.DeepResearchConfig
import ai.koog.deepresearch.config.MCPConfig
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import kotlinx.coroutines.runBlocking

/**
 * Example showing how to use MCP (Model Context Protocol) tools with Deep Research.
 * 
 * This example demonstrates:
 * 1. Configuring MCP with STDIO transport (subprocess)
 * 2. Connecting to an MCP server (Google Maps example)
 * 3. Using MCP tools alongside built-in Tavily search
 * 
 * Mirrors Python's MCP configuration:
 * ```python
 * mcp_servers = {
 *     "google-maps": {
 *         "command": "npx",
 *         "args": ["-y", "@anthropic-ai/mcp-server-google-maps"],
 *         "env": {"GOOGLE_MAPS_API_KEY": "your-key"}
 *     }
 * }
 * ```
 */
fun main() = runBlocking {
    // Required API keys from environment
    val openaiApiKey = System.getenv("OPENAI_API_KEY")
        ?: error("Please set OPENAI_API_KEY environment variable")
    
    val tavilyApiKey = System.getenv("TAVILY_API_KEY")
        ?: error("Please set TAVILY_API_KEY environment variable")
    
    // Optional: Google Maps API key for MCP example
    val googleMapsApiKey = System.getenv("GOOGLE_MAPS_API_KEY")
    
    // Configure MCP - example with Google Maps MCP server
    val mcpConfig = if (googleMapsApiKey != null) {
        // Use STDIO transport to connect to MCP subprocess
        MCPConfig.stdio(
            command = listOf("npx", "-y", "@anthropic-ai/mcp-server-google-maps"),
            environment = mapOf("GOOGLE_MAPS_API_KEY" to googleMapsApiKey),
            // Optionally filter to specific tools
            tools = listOf("search_places", "get_directions", "get_place_details")
        )
    } else {
        println("⚠️ GOOGLE_MAPS_API_KEY not set - running without MCP tools")
        println("   To enable MCP, set: export GOOGLE_MAPS_API_KEY=your-api-key")
        println()
        null
    }
    
    // Configure Deep Research with MCP
    val config = DeepResearchConfig(
        tavilyApiKey = tavilyApiKey,
        researchModel = OpenAIModels.Chat.GPT4o,
        supervisorModel = OpenAIModels.Chat.GPT4o,
        finalReportModel = OpenAIModels.Chat.GPT4o,
        maxResearchLoops = 2,
        maxReactToolCalls = 5,
        mcpConfig = mcpConfig,
        // Custom prompt explaining MCP tools to the agent
        mcpPrompt = if (mcpConfig != null) {
            """
            You have access to Google Maps tools via MCP:
            - search_places: Search for places by name or category
            - get_directions: Get directions between two locations
            - get_place_details: Get detailed information about a place
            
            Use these for location-based research tasks.
            """.trimIndent()
        } else null
    )
    
    // Create the executor and agent
    simpleOpenAIExecutor(openaiApiKey).use { executor ->
        val agent = DeepResearchAgent(config, executor)
        
        // Example query that benefits from location tools
        val query = if (mcpConfig != null) {
            """
            What are the top-rated vegetarian restaurants in San Francisco's Mission District?
            Include details about their locations, ratings, and what makes them special.
            Compare at least 3 restaurants.
            """
        } else {
            """
            What are the key differences between San Francisco and New York City 
            for technology startup founders? Consider factors like cost of living, 
            talent availability, investor access, and quality of life.
            """
        }
        
        println("=" .repeat(60))
        println("Deep Research with MCP Integration")
        println("=" .repeat(60))
        println("Query: ${query.trim()}")
        println()
        
        try {
            val result = agent.research(query.trimIndent())
            
            println("=" .repeat(60))
            println("RESEARCH REPORT")
            println("=" .repeat(60))
            println(result.report)
            println()
            println("Sources: ${result.sources.size}")
            result.sources.take(5).forEachIndexed { i, source ->
                println("  ${i+1}. $source")
            }
            
        } finally {
            // Clean up MCP processes
            ai.koog.deepresearch.tools.MCPToolsLoader.cleanup()
        }
    }
}
