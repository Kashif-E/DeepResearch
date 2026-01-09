package ai.koog.deepresearch.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.agents.mcp.defaultStdioTransport
import ai.koog.deepresearch.config.DeepResearchConfig
import ai.koog.deepresearch.config.MCPConfig
import ai.koog.deepresearch.config.MCPTransport
import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

/**
 * MCP (Model Context Protocol) tools loader for Deep Research.
 * 
 * Provides integration with MCP servers to extend research capabilities
 * with external tools like Google Search, Playwright, etc.
 * 
 * Mirrors Python: load_mcp_tools() in utils.py
 */
object MCPToolsLoader {
    
    // Track active MCP processes for cleanup
    private val activeProcesses = mutableListOf<Process>()
    
    /**
     * Load MCP tools from configured MCP servers.
     * 
     * @param config Deep Research configuration containing MCP settings
     * @param existingToolNames Set of tool names already in use to avoid conflicts
     * @return ToolRegistry containing MCP tools, or empty registry if not configured
     */
    suspend fun loadMCPTools(
        config: DeepResearchConfig,
        existingToolNames: Set<String> = emptySet()
    ): ToolRegistry {
        val mcpConfig = config.mcpConfig ?: return ToolRegistry.EMPTY
        
        return try {
            val transport = createTransport(mcpConfig)
            
            logger.info { "Connecting to MCP server..." }
            
            val fullRegistry = McpToolRegistryProvider.fromTransport(
                transport = transport,
                name = "koog-deep-research",
                version = "1.0.0"
            )
            
            // Filter tools if specific ones are requested
            val filteredTools = filterTools(fullRegistry.tools, mcpConfig.tools, existingToolNames)
            
            logger.info { "Loaded ${filteredTools.size} MCP tools: ${filteredTools.map { it.name }}" }
            
            // Create a new registry with filtered tools
            ToolRegistry {
                filteredTools.forEach { tool(it) }
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to load MCP tools: ${e.message}" }
            ToolRegistry.EMPTY
        }
    }
    
    /**
     * Create transport based on MCP configuration.
     */
    private suspend fun createTransport(mcpConfig: MCPConfig): Transport {
        return when (mcpConfig.transport) {
            MCPTransport.SSE -> {
                val url = mcpConfig.url 
                    ?: throw IllegalArgumentException("SSE transport requires URL")
                logger.info { "Creating SSE transport to: $url" }
                McpToolRegistryProvider.defaultSseTransport(url)
            }
            
            MCPTransport.STDIO -> {
                val command = mcpConfig.command
                    ?: throw IllegalArgumentException("STDIO transport requires command")
                
                logger.info { "Starting MCP subprocess: ${command.joinToString(" ")}" }
                
                val process = withContext(Dispatchers.IO) {
                    val processBuilder = ProcessBuilder(command)
                    
                    // Add environment variables
                    mcpConfig.environment.forEach { (key, value) ->
                        processBuilder.environment()[key] = value
                    }
                    
                    processBuilder.start()
                }
                
                // Track process for cleanup
                activeProcesses.add(process)
                
                // Give the process time to start
                withContext(Dispatchers.IO) {
                    Thread.sleep(2000)
                }
                
                McpToolRegistryProvider.defaultStdioTransport(process)
            }
        }
    }
    
    /**
     * Filter tools based on configuration and avoid conflicts.
     */
    private fun filterTools(
        allTools: List<Tool<*, *>>,
        requestedTools: List<String>?,
        existingToolNames: Set<String>
    ): List<Tool<*, *>> {
        return allTools.filter { tool ->
            // Skip tools with conflicting names
            if (tool.name in existingToolNames) {
                logger.warn { "MCP tool '${tool.name}' conflicts with existing tool - skipping" }
                return@filter false
            }
            
            // If specific tools requested, only include those
            if (requestedTools != null && tool.name !in requestedTools) {
                return@filter false
            }
            
            true
        }
    }
    
    /**
     * Cleanup all active MCP processes.
     * Should be called when shutting down Deep Research.
     */
    fun cleanup() {
        logger.info { "Cleaning up ${activeProcesses.size} MCP processes..." }
        activeProcesses.forEach { process ->
            try {
                process.destroyForcibly()
            } catch (e: Exception) {
                logger.warn { "Failed to destroy MCP process: ${e.message}" }
            }
        }
        activeProcesses.clear()
    }
    
    /**
     * Get description of MCP tools for prompt context.
     * Mirrors Python: configurable.mcp_prompt
     */
    fun getMCPPrompt(config: DeepResearchConfig): String? {
        val mcpConfig = config.mcpConfig ?: return null
        
        val toolsList = mcpConfig.tools?.joinToString(", ") ?: "all available"
        
        return """
            |You have access to additional tools via Model Context Protocol (MCP):
            |Available MCP tools: $toolsList
            |
            |Use these tools when appropriate for your research tasks.
        """.trimMargin()
    }
}

/**
 * Extension to merge MCP tools with existing tool registry.
 */
suspend fun ToolRegistry.withMCPTools(
    config: DeepResearchConfig,
    existingToolNames: Set<String> = this.tools.map { it.name }.toSet()
): ToolRegistry {
    val mcpRegistry = MCPToolsLoader.loadMCPTools(config, existingToolNames)
    
    if (mcpRegistry.tools.isEmpty()) {
        return this
    }
    
    // Merge registries
    return ToolRegistry {
        this@withMCPTools.tools.forEach { tool(it) }
        mcpRegistry.tools.forEach { tool(it) }
    }
}
