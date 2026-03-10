package ai.kash.delve.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.agents.mcp.defaultStdioTransport
import ai.kash.delve.config.DeepResearchConfig
import ai.kash.delve.config.MCPConfig
import ai.kash.delve.config.MCPTransport
import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

private val logger = KotlinLogging.logger {}

object MCPToolsLoader {

    private val activeProcesses = CopyOnWriteArrayList<Process>()

    private fun resolvedConfigs(config: DeepResearchConfig): Map<String, MCPConfig>? {
        return when {
            config.mcpConfigs.isNotEmpty() -> config.mcpConfigs
            config.mcpConfig != null -> mapOf("default" to config.mcpConfig)
            else -> null
        }
    }

    suspend fun loadMCPTools(
        config: DeepResearchConfig,
        existingToolNames: Set<String> = emptySet()
    ): ToolRegistry {
        val allConfigs = resolvedConfigs(config) ?: return ToolRegistry.EMPTY

        val allTools = mutableListOf<Tool<*, *>>()
        val usedNames = existingToolNames.toMutableSet()

        for ((name, mcpConfig) in allConfigs) {
            try {
                val transport = createTransport(mcpConfig)

                logger.info { "Connecting to MCP server '$name'..." }

                val fullRegistry = McpToolRegistryProvider.fromTransport(
                    transport = transport,
                    name = "koog-deep-research-$name",
                    version = "1.0.0"
                )

                val filteredTools = filterTools(fullRegistry.tools, mcpConfig.tools, usedNames)
                usedNames.addAll(filteredTools.map { it.name })
                allTools.addAll(filteredTools)

                logger.info { "Loaded ${filteredTools.size} MCP tools from '$name': ${filteredTools.map { it.name }}" }

            } catch (e: Exception) {
                logger.error(e) { "Failed to load MCP tools from '$name': ${e.message}" }
            }
        }

        return ToolRegistry {
            allTools.forEach { tool(it) }
        }
    }

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
                    mcpConfig.environment.forEach { (key, value) ->
                        processBuilder.environment()[key] = value
                    }
                    processBuilder.start()
                }

                activeProcesses.add(process)
                delay(2000)

                McpToolRegistryProvider.defaultStdioTransport(process)
            }
        }
    }

    private fun filterTools(
        allTools: List<Tool<*, *>>,
        requestedTools: List<String>?,
        existingToolNames: Set<String>
    ): List<Tool<*, *>> {
        return allTools.filter { tool ->
            if (tool.name in existingToolNames) {
                logger.warn { "MCP tool '${tool.name}' conflicts with existing tool - skipping" }
                return@filter false
            }
            if (requestedTools != null && tool.name !in requestedTools) {
                return@filter false
            }
            true
        }
    }

    fun cleanup() {
        logger.info { "Cleaning up ${activeProcesses.size} MCP processes..." }
        activeProcesses.forEach { process ->
            try {
                process.destroy()
                if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            } catch (e: Exception) {
                logger.warn { "Failed to destroy MCP process: ${e.message}" }
            }
        }
        activeProcesses.clear()
    }

    fun getMCPPrompt(config: DeepResearchConfig): String? {
        val allConfigs = resolvedConfigs(config) ?: return null

        val serverDescriptions = allConfigs.map { (name, mcpConfig) ->
            val toolsList = mcpConfig.tools?.joinToString(", ") ?: "all available"
            "- $name: $toolsList"
        }.joinToString("\n")

        return """
            |You have access to additional tools via Model Context Protocol (MCP):
            |$serverDescriptions
            |
            |Use these tools when appropriate for your research tasks.
        """.trimMargin()
    }
}
