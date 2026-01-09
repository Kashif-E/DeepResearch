package ai.koog.deepresearch

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.deepresearch.agents.SupervisorAgent
import ai.koog.deepresearch.config.DeepResearchConfig
import ai.koog.deepresearch.model.ClarificationResult
import ai.koog.deepresearch.model.FinalReport
import ai.koog.deepresearch.model.ResearchBrief
import ai.koog.deepresearch.prompts.ResearchPrompts
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

/**
 * Main Deep Research Agent that orchestrates the entire research process.
 * 
 * The research workflow follows these phases:
 * 1. **Clarification**: Analyze user input and ask clarifying questions if needed
 * 2. **Research Brief**: Transform user request into detailed research question
 * 3. **Supervision**: Delegate research to sub-agents and coordinate findings
 * 4. **Final Report**: Generate comprehensive report from all findings
 * 
 * Usage:
 * ```kotlin
 * val agent = DeepResearchAgent(config, promptExecutor)
 * val report = agent.research("What are the latest developments in AI safety?")
 * println(report.report)
 * ```
 */
class DeepResearchAgent(
    private val config: DeepResearchConfig = DeepResearchConfig.fromEnvironment(),
    private val promptExecutor: PromptExecutor
) {
    
    /**
     * Execute the full deep research workflow.
     * 
     * @param userQuery The user's research question or request
     * @return FinalReport containing the comprehensive research report
     */
    suspend fun research(userQuery: String): FinalReport {
        logger.info { "Starting deep research for query: ${userQuery.take(100)}..." }
        
        // Phase 1: Clarification (optional)
        val clarifiedQuery = if (config.enableClarification) {
            clarifyUserInput(userQuery)
        } else {
            userQuery
        }
        
        // Phase 2: Generate Research Brief
        val researchBrief = generateResearchBrief(clarifiedQuery)
        logger.info { "Research brief generated: ${researchBrief.take(200)}..." }
        
        // Phase 3: Supervision - Delegate and coordinate research
        val supervisor = SupervisorAgent(config, promptExecutor)
        val supervisionResult = supervisor.supervise(researchBrief)
        
        logger.info { "Research supervision completed. Collected ${supervisionResult.notes.size} notes over ${supervisionResult.iterations} iterations" }
        
        // Phase 4: Generate Final Report
        val finalReport = generateFinalReport(
            researchBrief = researchBrief,
            userQuery = userQuery,
            notes = supervisionResult.notes
        )
        
        logger.info { "Deep research completed successfully" }
        
        return FinalReport(
            report = finalReport,
            sources = extractSources(finalReport),
            researchBrief = researchBrief
        )
    }
    
    /**
     * Phase 1: Analyze user input and determine if clarification is needed.
     * 
     * If clarification is needed, this would typically involve user interaction.
     * For this implementation, we proceed with the original query if no
     * critical ambiguities are detected.
     */
    private suspend fun clarifyUserInput(userQuery: String): String {
        logger.debug { "Analyzing user input for clarification needs..." }
        
        try {
            val clarificationConfig = AIAgentConfig(
                prompt = prompt("clarification") {
                    system("You are analyzing a user's research request to determine if clarification is needed.")
                },
                model = config.researchModel,
                maxAgentIterations = 100
            )
            
            val agent = AIAgent(
                promptExecutor = promptExecutor,
                agentConfig = clarificationConfig,
                toolRegistry = ToolRegistry.EMPTY
            )
            
            val clarificationPrompt = ResearchPrompts.clarifyWithUserInstructions(userQuery)
            val result = agent.run(clarificationPrompt)
            
            // Parse the result - in a real implementation, this would involve
            // structured output parsing and potential user interaction
            // For now, we log and continue with the original query
            logger.debug { "Clarification analysis: $result" }
            
            return userQuery
            
        } catch (e: Exception) {
            logger.warn(e) { "Clarification analysis failed, proceeding with original query" }
            return userQuery
        }
    }
    
    /**
     * Phase 2: Transform user input into a detailed research brief.
     */
    private suspend fun generateResearchBrief(userQuery: String): String {
        logger.debug { "Generating research brief..." }
        
        val briefConfig = AIAgentConfig(
            prompt = prompt("research_brief") {
                system("You are transforming a user's research request into a detailed research brief.")
            },
            model = config.researchModel,
            maxAgentIterations = 100
        )
        
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            agentConfig = briefConfig,
            toolRegistry = ToolRegistry.EMPTY
        )
        
        val briefPrompt = ResearchPrompts.transformMessagesIntoResearchBrief(userQuery)
        return agent.run(briefPrompt)
    }
    
    /**
     * Phase 4: Generate the final comprehensive report from all findings.
     */
    private suspend fun generateFinalReport(
        researchBrief: String,
        userQuery: String,
        notes: List<String>
    ): String {
        logger.debug { "Generating final report from ${notes.size} research notes..." }
        
        val reportConfig = AIAgentConfig(
            prompt = prompt("final_report") {
                system("You are writing a comprehensive research report based on gathered findings.")
            },
            model = config.finalReportModel,
            maxAgentIterations = 100
        )
        
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            agentConfig = reportConfig,
            toolRegistry = ToolRegistry.EMPTY
        ) {
            handleEvents {
                onAgentExecutionFailed { ctx ->
                    logger.error(ctx.throwable) { "Final report generation failed" }
                }
            }
        }
        
        val findings = notes.joinToString("\n\n---\n\n")
        val reportPrompt = ResearchPrompts.finalReportGenerationPrompt(
            researchBrief = researchBrief,
            messages = userQuery,
            findings = findings
        )
        
        return agent.run(reportPrompt)
    }
    
    /**
     * Extract source URLs from the final report.
     */
    private fun extractSources(report: String): List<String> {
        // Simple regex to extract URLs from the report
        val urlPattern = Regex("""https?://[^\s\])"'>]+""")
        return urlPattern.findAll(report)
            .map { it.value.trimEnd('.', ',', ')', ']') }
            .distinct()
            .toList()
    }
    
    companion object {
        /**
         * Create a DeepResearchAgent with default OpenAI configuration.
         */
        fun withOpenAI(
            apiKey: String = System.getenv("OPENAI_API_KEY") ?: error("OPENAI_API_KEY not set"),
            config: DeepResearchConfig = DeepResearchConfig.fromEnvironment()
        ): Pair<DeepResearchAgent, PromptExecutor> {
            val executor = simpleOpenAIExecutor(apiKey)
            return Pair(DeepResearchAgent(config, executor), executor)
        }
    }
}

/**
 * Main entry point for running the Deep Research Agent.
 */
fun main() = runBlocking {
    val apiKey = System.getenv("OPENAI_API_KEY") 
        ?: error("Please set OPENAI_API_KEY environment variable")
    
    val tavilyKey = System.getenv("TAVILY_API_KEY")
    if (tavilyKey.isNullOrEmpty()) {
        logger.warn { "TAVILY_API_KEY not set - search functionality will be limited" }
    }
    
    // Create configuration
    val config = DeepResearchConfig(
        maxReactToolCalls = 4,
        maxConcurrentResearchUnits = 3,
        enableClarification = false // Disable for CLI usage
    )
    
    // Create executor and agent
    simpleOpenAIExecutor(apiKey).use { executor ->
        val agent = DeepResearchAgent(config, executor)
        
        // Example research query
        val query = """
            Compare the AI safety approaches of OpenAI, Anthropic, and DeepMind.
            Focus on their published research, safety teams, and public commitments.
        """.trimIndent()
        
        println("=" .repeat(80))
        println("KOOG DEEP RESEARCH AGENT")
        println("=" .repeat(80))
        println()
        println("Research Query:")
        println(query)
        println()
        println("-".repeat(80))
        println("Starting research...")
        println("-".repeat(80))
        println()
        
        try {
            val report = agent.research(query)
            
            println("=" .repeat(80))
            println("FINAL REPORT")
            println("=" .repeat(80))
            println()
            println(report.report)
            println()
            println("-".repeat(80))
            println("Sources (${report.sources.size}):")
            report.sources.forEachIndexed { index, source ->
                println("  [${index + 1}] $source")
            }
            println("-".repeat(80))
            
        } catch (e: Exception) {
            logger.error(e) { "Research failed" }
            println("Error: ${e.message}")
        }
    }
}
