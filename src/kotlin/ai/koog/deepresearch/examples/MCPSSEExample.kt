package ai.koog.deepresearch.examples

import ai.koog.deepresearch.DeepResearchAgent
import ai.koog.deepresearch.config.DeepResearchConfig
import ai.koog.deepresearch.config.MCPConfig
import ai.koog.deepresearch.tools.MCPToolsLoader
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import kotlinx.coroutines.runBlocking

/**
 * Example showing how to use MCP with SSE (Server-Sent Events) transport.
 * 
 * SSE transport is useful when:
 * - The MCP server is running as a remote service
 * - You want to share an MCP server across multiple clients
 * - You're using a hosted MCP service
 * 
 * This mirrors Python's MCP SSE configuration:
 * ```python
 * mcp_servers = {
 *     "my-mcp-server": {
 *         "url": "http://localhost:8080/sse",
 *         "transport": "sse"
 *     }
 * }
 * ```
 */
fun main() = runBlocking {
    // Required API keys
    val openaiApiKey = System.getenv("OPENAI_API_KEY")
        ?: error("Please set OPENAI_API_KEY environment variable")
    
    val tavilyApiKey = System.getenv("TAVILY_API_KEY")
        ?: error("Please set TAVILY_API_KEY environment variable")
    
    // MCP server URL (running separately)
    val mcpServerUrl = System.getenv("MCP_SERVER_URL")
    
    if (mcpServerUrl == null) {
        println("""
            ┌────────────────────────────────────────────────────────────────┐
            │                     SSE MCP Example Setup                      │
            ├────────────────────────────────────────────────────────────────┤
            │                                                                │
            │ This example requires an MCP server running with SSE support.  │
            │                                                                │
            │ To run an MCP server with SSE:                                 │
            │                                                                │
            │ 1. Start an MCP server with HTTP/SSE transport:                │
            │    npx @anthropic-ai/mcp-server-example --transport sse        │
            │                                                                │
            │ 2. Set the URL environment variable:                           │
            │    export MCP_SERVER_URL=http://localhost:8080/sse             │
            │                                                                │
            │ 3. Run this example again                                      │
            │                                                                │
            │ Alternatively, use MCPExample.kt for STDIO transport which     │
            │ automatically starts the MCP server as a subprocess.           │
            └────────────────────────────────────────────────────────────────┘
        """.trimIndent())
        return@runBlocking
    }
    
    // Configure MCP with SSE transport
    val mcpConfig = MCPConfig.sse(
        url = mcpServerUrl,
        // Optionally specify which tools to use
        tools = null // null means use all available tools
    )
    
    // Configure Deep Research with MCP
    val config = DeepResearchConfig(
        tavilyApiKey = tavilyApiKey,
        researchModel = OpenAIModels.Chat.GPT4o,
        supervisorModel = OpenAIModels.Chat.GPT4o,
        finalReportModel = OpenAIModels.Chat.GPT4o,
        maxResearchLoops = 2,
        maxReactToolCalls = 5,
        mcpConfig = mcpConfig,
        mcpPrompt = """
            You have access to additional tools via MCP (Model Context Protocol).
            Use these tools when they are relevant to your research task.
            The available tools will be listed in your tool descriptions.
        """.trimIndent()
    )
    
    // Create the executor and agent
    simpleOpenAIExecutor(openaiApiKey).use { executor ->
        val agent = DeepResearchAgent(config, executor)
        
        // Research query
        val query = """
            What are the latest developments in AI safety research?
            Focus on alignment techniques and interpretability methods
            from the past year.
        """
        
        println("=" .repeat(60))
        println("Deep Research with MCP SSE Transport")
        println("=" .repeat(60))
        println("MCP Server: $mcpServerUrl")
        println("Query: ${query.trim()}")
        println()
        
        try {
            val result = agent.research(query.trimIndent())
            
            println("=" .repeat(60))
            println("RESEARCH REPORT")
            println("=" .repeat(60))
            println(result.report)
            
        } finally {
            MCPToolsLoader.cleanup()
        }
    }
}
