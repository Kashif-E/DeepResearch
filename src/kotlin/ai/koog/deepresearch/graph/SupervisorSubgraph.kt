package ai.koog.deepresearch.graph

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.ToolCalls
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.deepresearch.config.DeepResearchConfig
import ai.koog.deepresearch.prompts.ResearchPrompts
import ai.koog.deepresearch.state.*
import ai.koog.deepresearch.utils.*
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private val logger = KotlinLogging.logger {}

/**
 * Supervisor Subgraph Implementation.
 * 
 * Mirrors the Python supervisor_builder StateGraph that coordinates research.
 * Flow: START -> supervisor -> supervisor_tools -> (loop or END)
 * 
 * The supervisor delegates research tasks to researcher subgraphs and
 * coordinates overall research progress.
 */
class SupervisorSubgraph(
    private val config: DeepResearchConfig,
    private val promptExecutor: PromptExecutor
) {
    
    // Researcher subgraph for spawning individual researchers
    private val researcherSubgraph = ResearcherSubgraph(config, promptExecutor)
    
    /**
     * Execute the supervisor subgraph.
     * Mirrors Python supervisor_subgraph.ainvoke()
     */
    suspend fun invoke(input: SupervisorState): SupervisorState {
        val state = input.copy()
        
        logger.info { "Supervisor starting with ${state.supervisorMessages.size} initial messages" }
        
        // Research loop: supervisor -> supervisor_tools
        while (state.iterationCount < config.maxSupervisorIterations) {
            // Step 1: Call supervisor node
            val supervisorResult = supervisor(state)
            
            // Check if research is complete
            if (supervisorResult is SupervisorNodeResult.Complete) {
                logger.info { "Supervisor: Research complete!" }
                break
            }
            
            // Step 2: Call supervisor_tools node
            val toolsResult = supervisorTools(state)
            
            // Check completion from tools
            if (toolsResult == SupervisorToolsResult.COMPLETE) {
                break
            }
            
            state.iterationCount++
        }
        
        if (state.iterationCount >= config.maxSupervisorIterations) {
            logger.warn { "Supervisor reached maximum iterations (${config.maxSupervisorIterations})" }
        }
        
        return state
    }
    
    /**
     * Supervisor node - coordinates research by delegating to researchers.
     * Mirrors Python supervisor() function.
     */
    private suspend fun supervisor(state: SupervisorState): SupervisorNodeResult {
        logger.debug { "Supervisor iteration ${state.iterationCount + 1}" }
        
        // Build system prompt with current date
        val systemPrompt = ResearchPrompts.supervisorSystemPrompt(config.mcpPrompt)
        
        // Build tool registry with supervisor tools
        val supervisorTools = SupervisorToolSet(config)
        val toolRegistry = ToolRegistry {
            tools(supervisorTools.asTools())
        }
        
        // Create agent with simple API
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = config.supervisorModel,
            systemPrompt = systemPrompt,
            toolRegistry = toolRegistry,
            strategy = singleRunStrategy(ToolCalls.SEQUENTIAL),
            temperature = config.temperature,
            maxIterations = 100
        )
        
        // Build messages for the agent
        val messagesContent = messagesToPromptString(state.supervisorMessages)
        
        try {
            val response = agent.run(messagesContent)
            
            // Parse response for tool calls
            val toolCalls = supervisorTools.getRecordedToolCalls()
            
            // Add AI response to state
            state.supervisorMessages.add(Message.AI(
                content = response,
                toolCalls = toolCalls.map { tc ->
                    ToolCall(id = tc.id, name = tc.name, args = tc.args)
                }
            ))
            
            // Check for research_complete tool call
            val researchComplete = toolCalls.any { 
                it.name.lowercase() == "researchcomplete" || it.name.lowercase() == "research_complete"
            }
            
            if (researchComplete) {
                return SupervisorNodeResult.Complete
            }
            
            return SupervisorNodeResult.Continue
            
        } catch (e: Exception) {
            logger.error(e) { "Supervisor error" }
            state.supervisorMessages.add(Message.AI(content = "Error: ${e.message}"))
            return SupervisorNodeResult.Complete
        }
    }
    
    /**
     * Supervisor tools node - handles tool calls and spawns researchers.
     * Mirrors Python supervisor_tools() function.
     * 
     * This is the key node that:
     * 1. Extracts tool calls from the supervisor's response
     * 2. For ConductResearch tools, spawns researcher subgraphs
     * 3. Aggregates results back to the supervisor
     */
    private suspend fun supervisorTools(state: SupervisorState): SupervisorToolsResult {
        val lastMessage = state.supervisorMessages.lastOrNull() as? Message.AI ?: return SupervisorToolsResult.COMPLETE
        val toolCalls = lastMessage.toolCalls
        
        if (toolCalls.isEmpty()) {
            return SupervisorToolsResult.COMPLETE
        }
        
        // Separate tool calls by type
        val conductResearchCalls = toolCalls.filter { 
            it.name.lowercase() == "conductresearch" || it.name.lowercase() == "conduct_research"
        }
        val otherToolCalls = toolCalls.filter { 
            it.name.lowercase() != "conductresearch" && it.name.lowercase() != "conduct_research"
        }
        
        // Check for research_complete
        val researchComplete = otherToolCalls.any { 
            it.name.lowercase() == "researchcomplete" || it.name.lowercase() == "research_complete"
        }
        
        if (researchComplete) {
            return SupervisorToolsResult.COMPLETE
        }
        
        // Spawn researchers for ConductResearch calls
        // Mirrors Python: research_tasks = list(asyncio.as_completed(...))
        if (conductResearchCalls.isNotEmpty()) {
            logger.info { "Supervisor dispatching ${conductResearchCalls.size} research task(s)" }
            
            val researchResults = coroutineScope {
                conductResearchCalls.map { toolCall ->
                    async {
                        spawnResearcher(toolCall, state)
                    }
                }.awaitAll()
            }
            
            // Add results to state
            for ((toolCall, result) in conductResearchCalls.zip(researchResults)) {
                state.supervisorMessages.add(Message.Tool(
                    content = result.compressedResearch,
                    name = toolCall.name,
                    toolCallId = toolCall.id
                ))
                
                // Accumulate raw notes
                state.rawNotes.addAll(result.rawNotes)
            }
            
            // Extract summaries from research results
            for (result in researchResults) {
                if (result.compressedResearch.isNotBlank() && !result.compressedResearch.startsWith("Error")) {
                    state.summaries.add(Summary(
                        summary = result.compressedResearch,
                        rawNotes = result.rawNotes
                    ))
                }
            }
        }
        
        // Handle other tool calls
        for (toolCall in otherToolCalls) {
            val result = when (toolCall.name.lowercase()) {
                "think", "think_tool" -> {
                    val reflection = toolCall.args["reflection"] ?: toolCall.args.values.firstOrNull() ?: ""
                    thinkTool(reflection)
                }
                else -> "Tool '${toolCall.name}' not recognized"
            }
            
            state.supervisorMessages.add(Message.Tool(
                content = result,
                name = toolCall.name,
                toolCallId = toolCall.id
            ))
        }
        
        return SupervisorToolsResult.CONTINUE
    }
    
    /**
     * Spawn a researcher subgraph for a conduct_research tool call.
     * Mirrors Python: researcher_subgraph.ainvoke({"research_topic": topic, ...})
     */
    private suspend fun spawnResearcher(toolCall: ToolCall, state: SupervisorState): ResearcherOutputState {
        logger.info { "Spawning researcher for tool call: ${toolCall.id}" }
        
        // Extract research topic from args
        val researchTopic = toolCall.args["topic"] 
            ?: toolCall.args["research_topic"]
            ?: toolCall.args.values.firstOrNull()
            ?: "General research task"
        
        // Create researcher state with context from supervisor
        val researcherState = ResearcherState(
            researchTopic = researchTopic,
            supervisorContext = state.supervisorMessages.take(5).joinToString("\n") { it.content }
        )
        
        return try {
            researcherSubgraph.invoke(researcherState)
        } catch (e: Exception) {
            logger.error(e) { "Researcher failed for topic: $researchTopic" }
            ResearcherOutputState(
                compressedResearch = "Error during research: ${e.message}",
                rawNotes = listOf("Research failed: ${e.message}")
            )
        }
    }
    
    sealed class SupervisorNodeResult {
        object Continue : SupervisorNodeResult()
        object Complete : SupervisorNodeResult()
    }
    
    enum class SupervisorToolsResult {
        CONTINUE,
        COMPLETE
    }
}

/**
 * Tools available to the supervisor.
 */
@LLMDescription("Supervisor tools for delegating research tasks")
class SupervisorToolSet(private val config: DeepResearchConfig) : ToolSet {
    
    private val recordedToolCalls = CopyOnWriteArrayList<RecordedToolCall>()
    
    @Tool
    @LLMDescription("Delegate a focused research task to a research assistant. The assistant will search the web and synthesize findings. Use this to investigate specific aspects of the user's query.")
    fun conductResearch(
        @LLMDescription("The specific topic or question for the researcher to investigate")
        topic: String
    ): String {
        recordedToolCalls.add(RecordedToolCall(
            id = UUID.randomUUID().toString(),
            name = "conductResearch",
            args = mapOf("topic" to topic)
        ))
        // Return is handled by supervisor_tools node
        return "Research task queued: $topic"
    }
    
    @Tool
    @LLMDescription("Signal that all research tasks are complete and sufficient information has been gathered to produce the final report.")
    fun researchComplete(
        @LLMDescription("Brief summary of why research is complete")
        reason: String
    ): String {
        recordedToolCalls.add(RecordedToolCall(
            id = UUID.randomUUID().toString(),
            name = "researchComplete",
            args = mapOf("reason" to reason)
        ))
        return "Research marked as complete: $reason"
    }
    
    @Tool
    @LLMDescription("Use this tool to think, reflect, and plan your research coordination strategy.")
    fun think(
        @LLMDescription("Your strategic thoughts about research coordination")
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
