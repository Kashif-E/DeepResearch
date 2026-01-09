package ai.koog.deepresearch.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.deepresearch.config.DeepResearchConfig
import ai.koog.deepresearch.model.CompressedResearch
import ai.koog.deepresearch.prompts.ResearchPrompts
import ai.koog.deepresearch.tools.SearchTools
import ai.koog.deepresearch.tools.ThinkTool
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Individual researcher agent that conducts focused research on a specific topic.
 * 
 * The researcher:
 * 1. Receives a specific research topic from the supervisor
 * 2. Uses search tools to gather comprehensive information
 * 3. Uses think tool to reflect on findings and plan next steps
 * 4. Returns compressed research findings
 * 
 * This agent is spawned by the supervisor for each research topic and operates
 * independently, focused solely on its assigned topic.
 */
class ResearcherAgent(
    private val config: DeepResearchConfig,
    private val promptExecutor: PromptExecutor
) {
    private val searchTools = SearchTools(config)
    private val thinkTool = ThinkTool()
    
    /**
     * Conduct research on the given topic.
     * 
     * @param researchTopic The specific topic to research
     * @return CompressedResearch containing findings and raw notes
     */
    suspend fun research(researchTopic: String): CompressedResearch {
        logger.info { "========================================" }
        logger.info { "RESEARCHER: Starting research on topic: ${researchTopic.take(100)}..." }
        logger.info { "========================================" }
        
        // Build tool registry for researcher
        val searchToolsList = searchTools.asTools()
        val thinkToolsList = thinkTool.asTools()
        logger.info { "RESEARCHER: Search tools: ${searchToolsList.map { it.name }}" }
        logger.info { "RESEARCHER: Think tools: ${thinkToolsList.map { it.name }}" }
        
        val toolRegistry = ToolRegistry {
            tools(searchToolsList)
            tools(thinkToolsList)
        }
        
        // Create researcher agent config
        val agentConfig = AIAgentConfig(
            prompt = prompt("researcher") {
                system(ResearchPrompts.researchSystemPrompt(config.mcpPrompt))
            },
            model = config.researchModel,
            maxAgentIterations = config.maxReactToolCalls * 4 // Allow for search + think + retry cycles
        )
        logger.info { "RESEARCHER: maxAgentIterations = ${config.maxReactToolCalls * 4}" }
        
        val rawNotes = mutableListOf<String>()
        
        try {
            // Create and run the researcher agent
            val agent = AIAgent(
                promptExecutor = promptExecutor,
                agentConfig = agentConfig,
                toolRegistry = toolRegistry
            ) {
                handleEvents {
                    onToolCallStarting { ctx ->
                        logger.info { "RESEARCHER >>> Tool starting: ${ctx.toolName}" }
                    }
                    
                    onToolCallCompleted { ctx ->
                        // Capture tool outputs as raw notes
                        val resultStr = ctx.toolResult?.toString() ?: ""
                        rawNotes.add("Tool: ${ctx.toolName}\nResult: $resultStr")
                        logger.info { "RESEARCHER <<< Tool ${ctx.toolName} completed with ${resultStr.length} chars" }
                    }
                    
                    onAgentExecutionFailed { ctx ->
                        logger.error(ctx.throwable) { "RESEARCHER !!! Execution failed" }
                    }
                }
            }
            
            // Run research
            val researchResult = agent.run(researchTopic)
            
            logger.info { "Research completed on topic: ${researchTopic.take(50)}..." }
            
            // Compress the research findings
            return compressResearch(researchResult, rawNotes)
            
        } catch (e: Exception) {
            logger.error(e) { "Error during research on topic: $researchTopic" }
            return CompressedResearch(
                compressedResearch = "Error conducting research: ${e.message}",
                rawNotes = rawNotes
            )
        } finally {
            searchTools.close()
        }
    }
    
    /**
     * Compress research findings into a structured summary.
     */
    private suspend fun compressResearch(
        researchResult: String,
        rawNotes: List<String>
    ): CompressedResearch {
        logger.debug { "Compressing research findings..." }
        
        try {
            // Create compression agent
            val compressionConfig = AIAgentConfig(
                prompt = prompt("compression") {
                    system(ResearchPrompts.compressResearchSystemPrompt())
                },
                model = config.compressionModel,
                maxAgentIterations = 5 // Single pass compression but needs buffer
            )
            
            val compressionAgent = AIAgent(
                promptExecutor = promptExecutor,
                agentConfig = compressionConfig,
                toolRegistry = ToolRegistry.EMPTY
            )
            
            // Build compression input from research result and raw notes
            val compressionInput = buildString {
                appendLine("## Research Findings")
                appendLine(researchResult)
                appendLine()
                appendLine("## Raw Tool Outputs")
                rawNotes.forEach { note ->
                    appendLine(note)
                    appendLine("---")
                }
                appendLine()
                appendLine(ResearchPrompts.compressResearchHumanMessage)
            }
            
            val compressedResult = compressionAgent.run(compressionInput)
            
            return CompressedResearch(
                compressedResearch = compressedResult,
                rawNotes = rawNotes
            )
            
        } catch (e: Exception) {
            logger.error(e) { "Error compressing research" }
            // Return uncompressed if compression fails
            return CompressedResearch(
                compressedResearch = researchResult,
                rawNotes = rawNotes
            )
        }
    }
}
