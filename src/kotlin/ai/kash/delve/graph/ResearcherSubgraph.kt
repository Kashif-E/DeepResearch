package ai.kash.delve.graph

import ai.kash.delve.config.DeepResearchConfig
import ai.kash.delve.prompts.ResearchPrompts
import ai.kash.delve.rag.DocumentRAG
import ai.kash.delve.tools.MCPToolsLoader
import ai.kash.delve.utils.SearchProgressCallbacks
import ai.kash.delve.utils.isTokenLimitExceeded
import ai.kash.delve.utils.retryWithBackoff
import ai.kash.delve.utils.thinkTool
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import ai.koog.agents.core.tools.Tool as KoogTool

private val logger = KotlinLogging.logger {}

@LLMDescription("Research tools for web search, document search, and strategic thinking")
class ResearcherTools(
    private val config: DeepResearchConfig,
    private val promptExecutor: PromptExecutor? = null,
    private val rag: DocumentRAG? = null,
    private val callbacks: SearchProgressCallbacks? = null
) : ToolSet {

    val rawNotes = CopyOnWriteArrayList<String>()

    @Tool
    @LLMDescription("Search the web for current information, facts, news, and research data.")
    suspend fun tavilySearch(
        @LLMDescription("The search query string")
        query: String
    ): String {
        return try {
            val queryList = query.split("|||").map { it.trim() }.filter { it.isNotBlank() }
            val result = ai.kash.delve.utils.tavilySearch(
                queryList, config,
                summarizationExecutor = promptExecutor,
                summarizationModel = config.summarizationModel,
                callbacks = callbacks
            )
            // Truncate raw notes to maxNoteLength to prevent unbounded memory growth
            rawNotes.add(result.take(config.maxNoteLength))
            result
        } catch (e: Exception) {
            val errorMsg = "Search failed for query '$query': ${e.message}. Try a different search query."
            logger.warn(e) { errorMsg }
            errorMsg
        }
    }

    @Tool
    @LLMDescription("Search through user-provided documents for relevant information. Use this FIRST before web search when documents are attached. Returns the most relevant passages from attached files.")
    suspend fun searchDocuments(
        @LLMDescription("The search query to find relevant passages in attached documents")
        query: String
    ): String {
        if (rag == null || !rag.isReady) {
            return "No documents are attached. Use tavilySearch for web search instead."
        }
        val context = rag.buildContext(query, topK = 5)
        if (context.isBlank()) return "No relevant passages found in attached documents."
        rawNotes.add(context)
        return context
    }

    @Tool
    @LLMDescription("Use this tool to think, reflect, and plan your research strategy. Use after each search to analyze results and plan next steps.")
    fun think(
        @LLMDescription("Your reflection, analysis, or strategic planning thoughts")
        reflection: String
    ): String {
        return thinkTool(reflection)
    }

    @Tool
    @LLMDescription("Call this when you have gathered enough information to answer the research question. This stops further searching and moves to summarizing your findings. Use this as soon as you have a clear answer — do not keep searching unnecessarily.")
    fun researchComplete(
        @LLMDescription("Brief summary of what you found and why you are stopping")
        summary: String
    ): String {
        return "Research complete. Provide your comprehensive findings now."
    }
}

data class ResearcherOutput(
    val compressedResearch: String,
    val rawNotes: List<String>
)

suspend fun runResearcher(
    topic: String,
    config: DeepResearchConfig,
    promptExecutor: PromptExecutor,
    rag: DocumentRAG? = null,
    mcpTools: List<KoogTool<*, *>> = emptyList(),
    callbacks: SearchProgressCallbacks? = null
): ResearcherOutput {
    logger.info { "Researcher starting: ${topic.take(80)}..." }

    val mcpPrompt = MCPToolsLoader.getMCPPrompt(config)
    val docHint = if (rag != null && rag.isReady) {
        "\n\nIMPORTANT: The user has attached documents. Use the searchDocuments tool FIRST to find relevant information before searching the web."
    } else ""
    val systemPrompt = ResearchPrompts.researchSystemPrompt(mcpPrompt ?: config.mcpPrompt) + docHint

    val tools = ResearcherTools(config, promptExecutor, rag, callbacks)
    val baseRegistry = ToolRegistry { tools(tools.asTools()) }

    val toolRegistry = if (mcpTools.isNotEmpty()) {
        logger.info { "Researcher using ${mcpTools.size} shared MCP tools" }
        ToolRegistry {
            baseRegistry.tools.forEach { tool(it) }
            mcpTools.forEach { tool(it) }
        }
    } else baseRegistry

    val agent = AIAgent(
        promptExecutor = promptExecutor,
        llmModel = config.researchModel,
        systemPrompt = systemPrompt,
        toolRegistry = toolRegistry,
        strategy = reactStrategy,
        temperature = config.researchTemperature ?: config.temperature,
        maxIterations = config.maxReactToolCalls * ITERATIONS_PER_TOOL_TURN
    )

    return try {
        val rawResult = retryWithBackoff { agent.run(topic) }

        val compressed = try {
            compressResearch(rawResult, tools.rawNotes, config, promptExecutor)
        } catch (e: Exception) {
            logger.warn(e) { "Research compression failed, using raw result" }
            rawResult
        }

        ResearcherOutput(
            compressedResearch = compressed,
            rawNotes = tools.rawNotes
        )
    } catch (e: Exception) {
        logger.error(e) { "Researcher failed: ${topic.take(80)}" }
        ResearcherOutput(
            compressedResearch = "Error during research: ${e.message}",
            rawNotes = tools.rawNotes
        )
    }
}

private suspend fun compressResearch(
    rawFindings: String,
    rawNotes: List<String>,
    config: DeepResearchConfig,
    promptExecutor: PromptExecutor
): String {
    if (rawFindings.isBlank()) return rawFindings

    val rawNotesBlock = if (rawNotes.isNotEmpty()) {
        "\n\n=== Raw Tool Outputs ===\n" + rawNotes.joinToString("\n---\n")
    } else ""

    var content = "$rawFindings$rawNotesBlock"
    var attempts = 0
    val maxAttempts = 3

    while (attempts < maxAttempts) {
        logger.info { "Compressing research findings (${content.length} chars, attempt ${attempts + 1})..." }

        val compressionPrompt = prompt("research-compression") {
            system(ResearchPrompts.compressResearchSystemPrompt())
            user("${ResearchPrompts.compressResearchHumanMessage}\n\n---\n\n$content")
        }

        try {
            return withTimeout(300_000) {
                val responses = promptExecutor.execute(
                    prompt = compressionPrompt,
                    model = config.compressionModel
                )
                responses.first().content
            }
        } catch (e: Exception) {
            attempts++
            if (isTokenLimitExceeded(e) && content.length > 2000) {
                content = content.take(content.length * 2 / 3)
                logger.warn { "Token limit during compression, truncating to ${content.length} chars (attempt $attempts)" }
            } else {
                throw e
            }
        }
    }

    return rawFindings
}
