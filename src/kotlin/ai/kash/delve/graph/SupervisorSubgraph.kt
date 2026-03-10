package ai.kash.delve.graph

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.Tool as KoogTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.asTools
import ai.kash.delve.config.DeepResearchConfig
import ai.kash.delve.prompts.ResearchPrompts
import ai.kash.delve.rag.DocumentRAG
import ai.kash.delve.state.ResearchQuestion
import ai.kash.delve.state.Summary
import ai.kash.delve.tools.MCPToolsLoader
import ai.kash.delve.utils.SearchProgressCallbacks
import ai.kash.delve.utils.retryWithBackoff
import ai.kash.delve.utils.thinkTool
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.CopyOnWriteArrayList

private val logger = KotlinLogging.logger {}

// conductResearch accepts multiple topics separated by ||| and runs them in parallel
@LLMDescription("Supervisor tools for delegating research tasks")
class SupervisorTools(
    private val config: DeepResearchConfig,
    private val promptExecutor: PromptExecutor,
    private val rag: DocumentRAG? = null,
    private val onResearchComplete: (suspend (Int, String) -> Unit)? = null,
    private val sharedMcpTools: List<KoogTool<*, *>> = emptyList(),
    private val callbacks: SearchProgressCallbacks? = null
) : ToolSet {

    val summaries = CopyOnWriteArrayList<Summary>()
    val rawNotes = CopyOnWriteArrayList<String>()
    private val concurrencySemaphore = Semaphore(config.maxConcurrentResearchUnits)

    @Tool
    @LLMDescription("Delegate research tasks to specialized research assistants. Each assistant will search the web and synthesize findings. You can pass multiple topics separated by ||| to run them in parallel. Example: 'Topic A ||| Topic B ||| Topic C'")
    suspend fun conductResearch(
        @LLMDescription("One or more research topics separated by |||")
        topic: String
    ): String {
        val topics = topic.split("|||").map { it.trim() }.filter { it.isNotBlank() }

        val maxTopics = config.maxConcurrentResearchUnits
        val accepted = topics.take(maxTopics)
        val rejected = topics.drop(maxTopics)

        if (rejected.isNotEmpty()) {
            logger.warn { "Rejecting ${rejected.size} topics: exceeds max concurrent research units ($maxTopics)" }
        }

        if (accepted.size == 1) {
            val result = executeResearch(accepted[0])
            return if (rejected.isEmpty()) result
            else "$result\n\nError: ${rejected.size} additional topic(s) were not researched because the maximum concurrent research units ($maxTopics) was exceeded."
        }

        logger.info { "Supervisor dispatching ${accepted.size} researchers in parallel" }
        val results = coroutineScope {
            accepted.map { t ->
                async {
                    concurrencySemaphore.withPermit {
                        executeResearch(t)
                    }
                }
            }.awaitAll()
        }

        val output = results.mapIndexed { i, r ->
            "--- Research ${i + 1}: ${accepted[i].take(60)} ---\n$r"
        }.joinToString("\n\n")

        return if (rejected.isEmpty()) output
        else "$output\n\nError: ${rejected.size} additional topic(s) were not researched because the maximum concurrent research units ($maxTopics) was exceeded."
    }

    private suspend fun executeResearch(singleTopic: String): String {
        logger.info { "Supervisor delegating: ${singleTopic.take(80)}..." }

        val output = runResearcher(singleTopic, config, promptExecutor, rag, sharedMcpTools, callbacks)

        if (output.compressedResearch.isNotBlank() && !output.compressedResearch.startsWith("Error")) {
            summaries.add(Summary(
                summary = output.compressedResearch,
                rawNotes = output.rawNotes
            ))
            onResearchComplete?.invoke(summaries.size, output.compressedResearch)
        }
        rawNotes.addAll(output.rawNotes)

        return output.compressedResearch
    }

    @Tool
    @LLMDescription("Signal that all research tasks are complete and sufficient information has been gathered to produce the final report.")
    fun researchComplete(
        @LLMDescription("Brief summary of why research is complete")
        reason: String
    ): String {
        return "Research marked as complete: $reason"
    }

    @Tool
    @LLMDescription("Use this tool to think, reflect, and plan your research coordination strategy.")
    fun think(
        @LLMDescription("Your strategic thoughts about research coordination")
        reflection: String
    ): String {
        return thinkTool(reflection)
    }
}

data class SupervisorOutput(
    val summaries: List<Summary>,
    val rawNotes: List<String>
)

suspend fun runSupervisor(
    researchBrief: String,
    config: DeepResearchConfig,
    promptExecutor: PromptExecutor,
    rag: DocumentRAG? = null,
    onResearchComplete: (suspend (Int, String) -> Unit)? = null,
    researchQuestions: List<ResearchQuestion> = emptyList(),
    callbacks: SearchProgressCallbacks? = null
): SupervisorOutput {
    logger.info { "Supervisor starting" }

    // Load MCP tools once at supervisor level to share across all researchers
    val mcpTools: List<KoogTool<*, *>> = if (config.mcpConfig != null || config.mcpConfigs.isNotEmpty()) {
        try {
            val registry = MCPToolsLoader.loadMCPTools(config)
            if (registry.tools.isNotEmpty()) {
                logger.info { "Loaded ${registry.tools.size} MCP tools for researchers" }
            }
            registry.tools
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load MCP tools" }
            emptyList()
        }
    } else emptyList()

    val systemPrompt = ResearchPrompts.leadResearcherPrompt(
        maxConcurrentResearchUnits = config.maxConcurrentResearchUnits,
        maxSupervisorIterations = config.maxSupervisorIterations
    )
    val tools = SupervisorTools(config, promptExecutor, rag, onResearchComplete, mcpTools, callbacks)
    val registeredTools = try {
        tools.asTools()
    } catch (e: Exception) {
        logger.error(e) { "Failed to register supervisor tools via reflection: ${e.javaClass.name}: ${e.message}" }
        throw e
    }
    logger.info { "Registered ${registeredTools.size} supervisor tools" }
    val toolRegistry = ToolRegistry { tools(registeredTools) }

    val agent = AIAgent(
        promptExecutor = promptExecutor,
        llmModel = config.supervisorModel,
        systemPrompt = systemPrompt,
        toolRegistry = toolRegistry,
        strategy = reactStrategy,
        temperature = config.supervisorTemperature ?: config.temperature,
        maxIterations = config.maxSupervisorIterations * ITERATIONS_PER_TOOL_TURN
    )

    val input = buildString {
        appendLine(researchBrief)
        // Feed structured research questions to the supervisor so it can use them
        // as pre-planned task assignments instead of decomposing the brief from scratch
        if (researchQuestions.isNotEmpty()) {
            appendLine()
            appendLine("Suggested research questions to investigate:")
            researchQuestions.forEachIndexed { i, q ->
                appendLine("${i + 1}. ${q.researchBrief}")
            }
            appendLine()
            appendLine("Use these questions as starting points for your research tasks. " +
                "You may combine related questions into a single conductResearch call or split them further as needed.")
        }
        appendLine()
        append("Research the above. First use the think tool to classify query complexity ")
        append("(simple factual / moderate / complex), then delegate the minimum number of ")
        append("research tasks needed. For simple factual questions, use just 1 research task and complete quickly. ")
        append("For complex queries, pass multiple topics in a single conductResearch call separated by ||| to run them in parallel.")
    }

    try {
        logger.info { "Supervisor agent executing..." }
        retryWithBackoff { agent.run(input) }
        logger.info { "Supervisor agent finished" }
    } catch (e: Exception) {
        logger.error(e) { "Supervisor error: ${e.javaClass.name}: ${e.message}" }
    }

    logger.info { "Supervisor completed with ${tools.summaries.size} summaries" }
    return SupervisorOutput(
        summaries = tools.summaries,
        rawNotes = tools.rawNotes
    )
}
