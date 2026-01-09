package ai.koog.deepresearch.graph

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.ToolCalls
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.deepresearch.config.DeepResearchConfig
import ai.koog.deepresearch.config.SearchAPI
import ai.koog.deepresearch.prompts.ResearchPrompts
import ai.koog.deepresearch.state.*
import ai.koog.deepresearch.tools.MCPToolsLoader
import ai.koog.deepresearch.tools.NativeSearchTools
import ai.koog.deepresearch.utils.*
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.params.LLMParams
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private val logger = KotlinLogging.logger {}

/**
 * Researcher Subgraph Implementation.
 * 
 * Mirrors the Python researcher_builder StateGraph that handles individual research tasks.
 * Flow: START -> researcher -> researcher_tools -> (loop or compress_research) -> END
 */
class ResearcherSubgraph(
    private val config: DeepResearchConfig,
    private val promptExecutor: PromptExecutor
) {
    
    /**
     * Execute the researcher subgraph for a specific topic.
     * Mirrors Python researcher_subgraph.ainvoke()
     */
    suspend fun invoke(input: ResearcherState): ResearcherOutputState {
        logger.info { "Researcher starting on topic: ${input.researchTopic.take(80)}..." }
        
        val state = input.copy()
        
        // Initialize with human message containing the research topic
        state.researcherMessages.add(Message.Human(state.researchTopic))
        
        // Research loop: researcher -> researcher_tools
        while (state.toolCallIterations < config.maxReactToolCalls) {
            // Step 1: Call researcher node
            val researcherResult = researcher(state)
            
            // Check if we should exit
            if (researcherResult is ResearcherNodeResult.GoToCompress) {
                break
            }
            
            // Step 2: Call researcher_tools node
            val toolsResult = researcherTools(state)
            
            // Check if we should compress and exit
            if (toolsResult == ResearcherToolsResult.COMPRESS) {
                break
            }
        }
        
        // Step 3: Compress research
        return compressResearch(state)
    }
    
    /**
     * Researcher node - invokes LLM with tools.
     * Mirrors Python researcher() function.
     * 
     * Supports three search modes:
     * - TAVILY: Uses Tavily search tool (default)
     * - OPENAI: Uses OpenAI native web search (model handles search internally)
     * - ANTHROPIC: Uses Anthropic native web search (model handles search internally)
     */
    private suspend fun researcher(state: ResearcherState): ResearcherNodeResult {
        logger.debug { "Researcher iteration ${state.toolCallIterations + 1}" }
        
        // Check if using native search (OpenAI or Anthropic)
        val useNativeSearch = NativeSearchTools.isNativeSearchEnabled(config)
        
        if (useNativeSearch) {
            return researcherWithNativeSearch(state)
        } else {
            return researcherWithTools(state)
        }
    }
    
    /**
     * Researcher using native web search (OpenAI or Anthropic built-in search).
     * The model handles web search internally - no explicit tool calls needed.
     */
    private suspend fun researcherWithNativeSearch(state: ResearcherState): ResearcherNodeResult {
        logger.info { "Using native search: ${NativeSearchTools.describeConfig(config)}" }
        
        // Build system prompt optimized for native search
        val mcpPrompt = MCPToolsLoader.getMCPPrompt(config)
        val systemPrompt = ResearchPrompts.researchSystemPromptForNativeSearch(
            searchApi = config.searchApi,
            mcpPrompt = mcpPrompt ?: config.mcpPrompt
        )
        
        // Get native search params
        val nativeParams = NativeSearchTools.createNativeSearchParams(config)
            ?: throw IllegalStateException("Native search enabled but params not created")
        
        // For native search, we only need the think tool (no search tool needed)
        val thinkTools = NativeSearchThinkTools(config)
        val toolRegistry = ToolRegistry {
            tools(thinkTools.asTools())
        }
        
        // Load MCP tools if configured
        val finalToolRegistry = if (config.mcpConfig != null) {
            try {
                val mcpTools = MCPToolsLoader.loadMCPTools(
                    config,
                    toolRegistry.tools.map { it.name }.toSet()
                )
                if (mcpTools.tools.isNotEmpty()) {
                    logger.info { "Using ${mcpTools.tools.size} MCP tools with native search" }
                    ToolRegistry {
                        toolRegistry.tools.forEach { tool(it) }
                        mcpTools.tools.forEach { tool(it) }
                    }
                } else {
                    toolRegistry
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load MCP tools" }
                toolRegistry
            }
        } else {
            toolRegistry
        }
        
        // Create agent with native search params
        val agentConfig = AIAgentConfig(
            prompt = prompt(
                id = "researcher-native-search",
                params = nativeParams
            ) {
                system(systemPrompt)
            },
            model = config.researchModel,
            maxAgentIterations = 10
        )
        
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            agentConfig = agentConfig,
            toolRegistry = finalToolRegistry,
            strategy = singleRunStrategy(ToolCalls.SEQUENTIAL)
        )
        
        val messagesContent = messagesToPromptString(state.researcherMessages)
        
        try {
            val response = agent.run(messagesContent)
            
            // With native search, the model performs search internally
            // The response contains the research findings directly
            state.researcherMessages.add(Message.AI(
                content = response,
                toolCalls = emptyList() // Native search doesn't use explicit tool calls
            ))
            
            state.toolCallIterations++
            state.rawNotes.add("Native Search Research:\n$response")
            
            // Check if model wants to continue researching (look for think tool calls)
            val thinkCalls = thinkTools.getRecordedToolCalls()
            if (thinkCalls.isEmpty() || state.toolCallIterations >= config.maxReactToolCalls) {
                return ResearcherNodeResult.GoToCompress
            }
            
            return ResearcherNodeResult.GoToTools
            
        } catch (e: Exception) {
            logger.error(e) { "Native search researcher error" }
            state.researcherMessages.add(Message.AI(content = "Error: ${e.message}"))
            return ResearcherNodeResult.GoToCompress
        }
    }
    
    /**
     * Researcher using tool-based search (Tavily or other external search tools).
     */
    private suspend fun researcherWithTools(state: ResearcherState): ResearcherNodeResult {
        // Build system prompt with MCP prompt if configured
        val mcpPrompt = MCPToolsLoader.getMCPPrompt(config)
        val systemPrompt = ResearchPrompts.researchSystemPrompt(mcpPrompt ?: config.mcpPrompt)
        
        // Build tool registry with search and think tools
        val researcherTools = ResearcherTools(config)
        val baseToolRegistry = ToolRegistry {
            tools(researcherTools.asTools())
        }
        
        // Load MCP tools if configured
        val toolRegistry = if (config.mcpConfig != null) {
            try {
                val mcpTools = MCPToolsLoader.loadMCPTools(
                    config, 
                    baseToolRegistry.tools.map { it.name }.toSet()
                )
                if (mcpTools.tools.isNotEmpty()) {
                    logger.info { "Using ${mcpTools.tools.size} MCP tools along with base tools" }
                    ToolRegistry {
                        baseToolRegistry.tools.forEach { tool(it) }
                        mcpTools.tools.forEach { tool(it) }
                    }
                } else {
                    baseToolRegistry
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load MCP tools, using base tools only" }
                baseToolRegistry
            }
        } else {
            baseToolRegistry
        }
        
        // Create agent with simple API
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = config.researchModel,
            systemPrompt = systemPrompt,
            toolRegistry = toolRegistry,
            strategy = singleRunStrategy(ToolCalls.SEQUENTIAL),
            temperature = config.temperature,
            maxIterations = 100
        )
        
        // Build messages for the agent
        val messagesContent = messagesToPromptString(state.researcherMessages)
        
        try {
            val response = agent.run(messagesContent)
            
            // Parse response for tool calls
            val toolCalls = researcherTools.getRecordedToolCalls()
            
            // Add AI response to state
            state.researcherMessages.add(Message.AI(
                content = response,
                toolCalls = toolCalls.map { tc ->
                    ToolCall(id = tc.id, name = tc.name, args = tc.args)
                }
            ))
            
            state.toolCallIterations++
            
            // Check if there are tool calls
            if (toolCalls.isEmpty()) {
                return ResearcherNodeResult.GoToCompress
            }
            
            return ResearcherNodeResult.GoToTools
            
        } catch (e: Exception) {
            logger.error(e) { "Researcher error" }
            state.researcherMessages.add(Message.AI(content = "Error: ${e.message}"))
            return ResearcherNodeResult.GoToCompress
        }
    }
    
    /**
     * Researcher tools node - executes tool calls.
     * Mirrors Python researcher_tools() function.
     */
    private suspend fun researcherTools(state: ResearcherState): ResearcherToolsResult {
        val lastMessage = state.researcherMessages.lastOrNull() as? Message.AI ?: return ResearcherToolsResult.COMPRESS
        val toolCalls = lastMessage.toolCalls
        
        if (toolCalls.isEmpty()) {
            return ResearcherToolsResult.COMPRESS
        }
        
        // Execute all tool calls
        for (toolCall in toolCalls) {
            val result = when (toolCall.name.lowercase()) {
                "tavilysearch", "search", "web_search" -> {
                    val queries = toolCall.args["queries"]?.split(",")?.map { it.trim() }
                        ?: listOf(toolCall.args["query"] ?: "")
                    tavilySearch(queries, config)
                }
                "think", "think_tool" -> {
                    val reflection = toolCall.args["reflection"] ?: toolCall.args.values.firstOrNull() ?: ""
                    thinkTool(reflection)
                }
                "researchcomplete" -> {
                    "Research complete signal received."
                }
                else -> {
                    "Unknown tool: ${toolCall.name}"
                }
            }
            
            // Add tool result to state
            state.researcherMessages.add(Message.Tool(
                content = result,
                name = toolCall.name,
                toolCallId = toolCall.id
            ))
            
            // Record raw notes
            state.rawNotes.add("Tool: ${toolCall.name}\nResult: $result")
        }
        
        // Check exit conditions
        val exceededIterations = state.toolCallIterations >= config.maxReactToolCalls
        val researchCompleted = toolCalls.any { it.name.lowercase() == "researchcomplete" }
        
        if (exceededIterations || researchCompleted) {
            return ResearcherToolsResult.COMPRESS
        }
        
        return ResearcherToolsResult.CONTINUE
    }
    
    /**
     * Compress research node - synthesizes findings.
     * Mirrors Python compress_research() function.
     */
    private suspend fun compressResearch(state: ResearcherState): ResearcherOutputState {
        logger.debug { "Compressing research findings..." }
        
        // Add compression instruction
        state.researcherMessages.add(Message.Human(ResearchPrompts.compressResearchHumanMessage))
        
        var synthesisAttempts = 0
        val maxAttempts = 3
        var currentMessages = state.researcherMessages.toMutableList()
        
        while (synthesisAttempts < maxAttempts) {
            try {
                // Build compression prompt
                val systemPrompt = ResearchPrompts.compressResearchSystemPrompt()
                
                val agent = AIAgent(
                    promptExecutor = promptExecutor,
                    llmModel = config.compressionModel,
                    systemPrompt = systemPrompt,
                    toolRegistry = ToolRegistry.EMPTY,
                    temperature = config.temperature,
                    maxIterations = 100
                )
                
                val messagesContent = messagesToPromptString(currentMessages)
                val response = agent.run(messagesContent)
                
                // Extract raw notes from messages
                val rawNotesContent = filterMessages(state.researcherMessages, setOf("tool", "assistant"))
                    .joinToString("\n") { it.content }
                
                return ResearcherOutputState(
                    compressedResearch = response,
                    rawNotes = state.rawNotes + rawNotesContent
                )
                
            } catch (e: Exception) {
                synthesisAttempts++
                
                if (isTokenLimitExceeded(e, config.compressionModel)) {
                    // Remove messages to reduce context
                    currentMessages = removeUpToLastAIMessage(currentMessages)
                    continue
                }
                
                logger.error(e) { "Compression attempt $synthesisAttempts failed" }
            }
        }
        
        // Return error if all attempts failed
        val rawNotesContent = state.researcherMessages
            .filter { it is Message.Tool || it is Message.AI }
            .joinToString("\n") { it.content }
        
        return ResearcherOutputState(
            compressedResearch = "Error synthesizing research report: Maximum retries exceeded",
            rawNotes = state.rawNotes + rawNotesContent
        )
    }
    
    sealed class ResearcherNodeResult {
        object GoToTools : ResearcherNodeResult()
        object GoToCompress : ResearcherNodeResult()
    }
    
    enum class ResearcherToolsResult {
        CONTINUE,
        COMPRESS
    }
}

/**
 * Tools available to the researcher.
 */
@LLMDescription("Research tools for web search and strategic thinking")
class ResearcherTools(private val config: DeepResearchConfig) : ToolSet {
    
    private val recordedToolCalls = CopyOnWriteArrayList<RecordedToolCall>()
    
    @Tool
    @LLMDescription("Search the web using Tavily API for comprehensive, accurate results. Use this to find current information, facts, news, and research data.")
    suspend fun tavilySearch(
        @LLMDescription("List of search queries to execute, comma-separated")
        queries: String
    ): String {
        val queryList = queries.split(",").map { it.trim() }.filter { it.isNotBlank() }
        recordedToolCalls.add(RecordedToolCall(
            id = UUID.randomUUID().toString(),
            name = "tavilySearch",
            args = mapOf("queries" to queries)
        ))
        return ai.koog.deepresearch.utils.tavilySearch(queryList, config)
    }
    
    @Tool
    @LLMDescription("Use this tool to think, reflect, and plan your research strategy. Use after each search to analyze results and plan next steps.")
    fun think(
        @LLMDescription("Your reflection, analysis, or strategic planning thoughts")
        reflection: String
    ): String {
        recordedToolCalls.add(RecordedToolCall(
            id = UUID.randomUUID().toString(),
            name = "think",
            args = mapOf("reflection" to reflection)
        ))
        return thinkTool(reflection)
    }
    
    fun getRecordedToolCalls(): List<RecordedToolCall> {
        val calls = recordedToolCalls.toList()
        recordedToolCalls.clear()
        return calls
    }
}

/**
 * Think tool only - used with native search where the model handles search internally.
 */
@LLMDescription("Strategic thinking tool for research reflection")
class NativeSearchThinkTools(private val config: DeepResearchConfig) : ToolSet {
    
    private val recordedToolCalls = CopyOnWriteArrayList<RecordedToolCall>()
    
    @Tool
    @LLMDescription("Use this tool to think, reflect, and plan your research strategy. Use after searching to analyze results and plan next steps. Do not call this tool in parallel with web search.")
    fun think(
        @LLMDescription("Your reflection, analysis, or strategic planning thoughts")
        reflection: String
    ): String {
        recordedToolCalls.add(RecordedToolCall(
            id = UUID.randomUUID().toString(),
            name = "think",
            args = mapOf("reflection" to reflection)
        ))
        return thinkTool(reflection)
    }
    
    fun getRecordedToolCalls(): List<RecordedToolCall> {
        val calls = recordedToolCalls.toList()
        recordedToolCalls.clear()
        return calls
    }
}

data class RecordedToolCall(
    val id: String,
    val name: String,
    val args: Map<String, String>
)
