package ai.koog.deepresearch.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.deepresearch.config.DeepResearchConfig
import ai.koog.deepresearch.prompts.ResearchPrompts
import ai.koog.deepresearch.tools.SupervisorTools
import ai.koog.deepresearch.tools.ThinkTool
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private val logger = KotlinLogging.logger {}

/**
 * Research supervisor agent that manages and delegates research tasks.
 * 
 * The supervisor:
 * 1. Receives the research brief from the main orchestrator
 * 2. Plans research strategy using think tool
 * 3. Delegates research tasks to researcher sub-agents via conductResearch
 * 4. Coordinates parallel research execution
 * 5. Signals completion when satisfied with findings
 * 
 * The supervisor operates as a manager, not performing research directly
 * but orchestrating multiple researcher agents to gather comprehensive information.
 */
class SupervisorAgent(
    private val config: DeepResearchConfig,
    private val promptExecutor: PromptExecutor
) {
    private val thinkTool = ThinkTool()
    private val supervisorTools = SupervisorTools()
    
    // Track research state
    private var researchIterations = 0
    private val collectedNotes = mutableListOf<String>()
    private val rawNotes = mutableListOf<String>()
    private var isResearchComplete = false
    
    // Pending research topics for parallel execution
    private val pendingResearchTopics = mutableListOf<String>()
    
    /**
     * Execute research supervision for the given brief.
     * 
     * @param researchBrief The detailed research question to investigate
     * @return List of all collected research notes
     */
    suspend fun supervise(researchBrief: String): SupervisionResult {
        logger.info { "Starting research supervision for brief: ${researchBrief.take(100)}..." }
        
        // Reset state
        researchIterations = 0
        collectedNotes.clear()
        rawNotes.clear()
        isResearchComplete = false
        pendingResearchTopics.clear()
        
        // Configure callbacks for supervisor tools
        configureSupervisorToolCallbacks()
        
        // Build tool registry
        val supervisorToolsList = supervisorTools.asTools()
        val thinkToolsList = thinkTool.asTools()
        logger.info { "Supervisor tools available: ${supervisorToolsList.map { it.name }}" }
        logger.info { "Think tools available: ${thinkToolsList.map { it.name }}" }
        
        val toolRegistry = ToolRegistry {
            tools(supervisorToolsList)
            tools(thinkToolsList)
        }
        
        // Create supervisor agent config
        val systemPrompt = ResearchPrompts.leadResearcherPrompt(
            maxConcurrentResearchUnits = config.maxConcurrentResearchUnits,
            maxResearcherIterations = config.maxResearchLoops
        )
        logger.info { "Supervisor system prompt length: ${systemPrompt.length} chars" }
        logger.info { "Using model: ${config.researchModel.id} (provider: ${config.researchModel.provider})" }
        logger.info { "Model capabilities: ${config.researchModel.capabilities}" }
        
        val agentConfig = AIAgentConfig(
            prompt = prompt("supervisor") {
                system(systemPrompt)
            },
            model = config.supervisorModel,
            maxAgentIterations = config.maxResearchLoops * 5 // Allow for think + research + assess cycles
        )
        logger.info { "Max agent iterations: ${config.maxResearchLoops * 5}" }
        
        try {
            // Run supervisor in a loop until research is complete or limit reached
            while (!isResearchComplete && researchIterations < config.maxResearchLoops) {
                researchIterations++
                logger.info { "Supervisor iteration $researchIterations/${config.maxResearchLoops}" }
                
                // Create supervisor agent for this iteration
                val agent = AIAgent(
                    promptExecutor = promptExecutor,
                    agentConfig = agentConfig,
                    toolRegistry = toolRegistry
                ) {
                    handleEvents {
                        onAgentStarting {
                            logger.info { "=== Agent starting ===" }
                        }
                        
                        onLLMCallStarting {
                            logger.info { "LLM call starting" }
                        }
                        
                        onLLMCallCompleted {
                            logger.info { "LLM call completed" }
                        }
                        
                        onToolCallStarting { ctx ->
                            logger.info { ">>> TOOL CALL: ${ctx.toolName}" }
                            logger.info { "    Args: ${ctx.toolArgs}" }
                            
                            // Check if this is a conductResearch call to queue for parallel execution
                            if (ctx.toolName == "conductResearch") {
                                // Extract the research topic from tool args (parameter name is 'task')
                                val topic = ctx.toolArgs["task"]?.toString()
                                    ?.removeSurrounding("\"") ?: ""
                                if (topic.isNotEmpty()) {
                                    logger.info { "    Queuing research topic: ${topic.take(80)}..." }
                                    pendingResearchTopics.add(topic)
                                } else {
                                    logger.warn { "    Empty research topic!" }
                                }
                            }
                        }
                        
                        onToolCallCompleted { ctx ->
                            val resultStr = ctx.toolResult?.toString() ?: ""
                            logger.info { "<<< TOOL COMPLETED: ${ctx.toolName} -> ${resultStr.take(100)}..." }
                        }
                        
                        onAgentExecutionFailed { ctx ->
                            logger.error(ctx.throwable) { "!!! Supervisor execution failed" }
                        }
                        
                        onAgentCompleted {
                            logger.info { "=== Agent completed ===" }
                        }
                    }
                }
                
                // Build input with context from previous iterations
                val supervisorInput = buildSupervisorInput(researchBrief)
                logger.info { "Supervisor input length: ${supervisorInput.length} chars" }
                logger.info { "Current state: iterations=$researchIterations, notes=${collectedNotes.size}, complete=$isResearchComplete" }
                
                // Run supervisor
                val result = agent.run(supervisorInput)
                logger.info { "Supervisor response: ${result.take(200)}..." }
                logger.info { "Pending research topics after iteration: ${pendingResearchTopics.size}" }
                
                // Execute any pending parallel research
                if (pendingResearchTopics.isNotEmpty()) {
                    logger.info { "Executing ${pendingResearchTopics.size} pending research topics..." }
                    executeParallelResearch()
                    logger.info { "After research: notes=${collectedNotes.size}, complete=$isResearchComplete" }
                } else {
                    logger.info { "No pending research topics this iteration" }
                }
                
                // Check for explicit completion signal
                if (isResearchComplete) {
                    logger.info { "Research complete signal received after ${collectedNotes.size} notes" }
                    break
                }
                
                // Log continuation
                logger.info { "Continuing to next iteration... (current: $researchIterations/${config.maxResearchLoops})" }
            }
            
            if (researchIterations >= config.maxResearchLoops) {
                logger.warn { "Maximum research iterations reached" }
            }
            
            return SupervisionResult(
                notes = collectedNotes.toList(),
                rawNotes = rawNotes.toList(),
                iterations = researchIterations
            )
            
        } catch (e: Exception) {
            logger.error(e) { "Error during research supervision" }
            return SupervisionResult(
                notes = collectedNotes.toList(),
                rawNotes = rawNotes.toList(),
                iterations = researchIterations,
                error = e.message
            )
        }
    }
    
    /**
     * Configure callbacks for the supervisor tools.
     */
    private fun configureSupervisorToolCallbacks() {
        logger.info { "Configuring supervisor tool callbacks..." }
        
        // The actual research execution happens in executeParallelResearch
        // Here we just return a placeholder that will be replaced
        supervisorTools.onConductResearch = { topic ->
            logger.info { "[CALLBACK] conductResearch called with topic: ${topic.take(100)}..." }
            // This will be called during tool execution
            // The actual parallel execution happens separately
            val response = "Research on topic queued for execution: ${topic.take(50)}..."
            logger.info { "[CALLBACK] conductResearch returning: $response" }
            response
        }
        
        supervisorTools.onResearchComplete = {
            // Only allow completion after at least 2 iterations with collected notes
            if (researchIterations >= 2 && collectedNotes.isNotEmpty()) {
                logger.info { "[CALLBACK] researchComplete called - setting isResearchComplete=true" }
                isResearchComplete = true
            } else {
                logger.info { "[CALLBACK] researchComplete called too early (iteration $researchIterations, notes: ${collectedNotes.size}) - ignoring" }
            }
        }
        
        logger.info { "Supervisor tool callbacks configured" }
    }
    
    /**
     * Execute all pending research topics in parallel.
     */
    private suspend fun executeParallelResearch() {
        if (pendingResearchTopics.isEmpty()) return
        
        logger.info { "Executing ${pendingResearchTopics.size} research tasks in parallel" }
        
        // Limit to max concurrent units
        val topicsToExecute = pendingResearchTopics.take(config.maxConcurrentResearchUnits)
        val overflowTopics = pendingResearchTopics.drop(config.maxConcurrentResearchUnits)
        
        // Log overflow
        if (overflowTopics.isNotEmpty()) {
            logger.warn { "${overflowTopics.size} research topics exceeded concurrent limit and were skipped" }
        }
        
        // Execute research in parallel
        val results = coroutineScope {
            topicsToExecute.map { topic ->
                async {
                    val researcher = ResearcherAgent(config, promptExecutor)
                    researcher.research(topic)
                }
            }.awaitAll()
        }
        
        // Collect results
        results.forEach { result ->
            collectedNotes.add(result.compressedResearch)
            rawNotes.addAll(result.rawNotes)
        }
        
        // Clear pending topics
        pendingResearchTopics.clear()
        
        logger.info { "Parallel research completed. Total notes collected: ${collectedNotes.size}" }
    }
    
    /**
     * Build input for supervisor with context from previous iterations.
     */
    private fun buildSupervisorInput(researchBrief: String): String {
        return buildString {
            appendLine("# Research Brief")
            appendLine(researchBrief)
            
            if (collectedNotes.isNotEmpty()) {
                appendLine()
                appendLine("# Research Findings So Far (${collectedNotes.size} notes from ${researchIterations} rounds)")
                collectedNotes.forEachIndexed { index, note ->
                    appendLine("## Finding ${index + 1}")
                    appendLine(note.take(config.maxNoteLength)) // Truncate for context window
                    appendLine()
                }
                
                appendLine()
                appendLine("# Assessment Required")
                appendLine("You have completed $researchIterations rounds of research and collected ${collectedNotes.size} notes.")
                appendLine()
                appendLine("Now use the 'think' tool to assess:")
                appendLine("1. What important aspects of the research question have NOT been covered yet?")
                appendLine("2. What areas need more depth or specific details?")
                appendLine("3. Are there opposing viewpoints or alternative perspectives to explore?")
                appendLine("4. Is there recent news or developments you haven't found yet?")
                appendLine()
                appendLine("If there are gaps, call 'conductResearch' to investigate them.")
                appendLine("Only call 'researchComplete' when you have COMPREHENSIVE coverage of ALL aspects.")
            } else {
                appendLine()
                appendLine("# Instructions")
                appendLine("This is your FIRST round of research. Break the research brief into multiple subtopics.")
                appendLine("Call 'conductResearch' multiple times with DIFFERENT topics to gather information from multiple angles.")
                appendLine("DO NOT call 'researchComplete' yet - you need at least 2 rounds of research.")
            }
        }
    }
}

/**
 * Result from supervision process.
 */
data class SupervisionResult(
    val notes: List<String>,
    val rawNotes: List<String>,
    val iterations: Int,
    val error: String? = null
)
