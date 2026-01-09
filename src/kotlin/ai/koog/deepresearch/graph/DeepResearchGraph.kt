package ai.koog.deepresearch.graph

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.deepresearch.config.DeepResearchConfig
import ai.koog.deepresearch.model.ClarificationDecision
import ai.koog.deepresearch.model.ResearchBriefWithQuestions
import ai.koog.deepresearch.prompts.ResearchPrompts
import ai.koog.deepresearch.state.*
import ai.koog.deepresearch.utils.*
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.structure.executeStructured
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.serializer

private val logger = KotlinLogging.logger {}

/**
 * Main Deep Research Graph Implementation.
 * 
 * Mirrors the Python deep_researcher_builder StateGraph.
 * 
 * Main Graph Flow:
 * START -> clarify_with_user -> write_research_brief -> research_supervisor -> final_report_generation -> END
 * 
 * This is a faithful port of the LangGraph implementation from Open Deep Research.
 */
class DeepResearchGraph(
    private val config: DeepResearchConfig,
    private val promptExecutor: PromptExecutor
) {
    
    // Supervisor subgraph for coordinating research
    private val supervisorSubgraph = SupervisorSubgraph(config, promptExecutor)
    
    /**
     * Execute the full research pipeline.
     * Mirrors Python: graph.invoke({"messages": [HumanMessage(query)]})
     * 
     * @param query The user's research query
     * @return AgentState containing the complete research result
     */
    suspend fun invoke(query: String): AgentState {
        val state = AgentState()
        state.messages.add(Message.Human(query))
        
        // Step 1: clarify_with_user
        logger.info { "Step 1/4: Clarifying user request..." }
        val clarifyResult = clarifyWithUser(state)
        
        if (clarifyResult is Command.End) {
            logger.info { "User clarification indicates no research needed. Ending." }
            state.finalReport = clarifyResult.update?.get("final_report")?.toString() ?: "No research conducted."
            return state
        }
        
        // Step 2: write_research_brief
        logger.info { "Step 2/4: Writing research brief..." }
        val briefResult = writeResearchBrief(state)
        
        // Step 3: research_supervisor (supervisorSubgraph)
        logger.info { "Step 3/4: Conducting research via supervisor..." }
        val researchResult = researchSupervisor(state)
        
        // Step 4: final_report_generation
        logger.info { "Step 4/4: Generating final report..." }
        val finalResult = finalReportGeneration(state)
        
        logger.info { "Research complete! Report length: ${state.finalReport.length} characters" }
        
        return state
    }
    
    /**
     * Stream the research process with progress updates.
     * Returns a Flow of events for real-time progress tracking.
     */
    fun stream(query: String): Flow<DeepResearchEvent> = flow {
        emit(DeepResearchEvent.Started(query))
        
        val state = AgentState()
        state.messages.add(Message.Human(query))
        
        // Step 1: clarify_with_user
        emit(DeepResearchEvent.Phase("clarify_with_user", "Analyzing your query..."))
        val clarifyResult = clarifyWithUser(state)
        
        if (clarifyResult is Command.End) {
            emit(DeepResearchEvent.Completed(state))
            return@flow
        }
        
        // Step 2: write_research_brief
        emit(DeepResearchEvent.Phase("write_research_brief", "Creating research plan..."))
        writeResearchBrief(state)
        emit(DeepResearchEvent.ResearchBrief(state.researchBrief))
        
        // Step 3: research_supervisor
        emit(DeepResearchEvent.Phase("research_supervisor", "Conducting research..."))
        researchSupervisor(state)
        
        // Emit summaries as they're collected
        for ((index, summary) in state.summaries.withIndex()) {
            emit(DeepResearchEvent.ResearchUpdate(index + 1, summary.summary))
        }
        
        // Step 4: final_report_generation
        emit(DeepResearchEvent.Phase("final_report_generation", "Generating final report..."))
        finalReportGeneration(state)
        
        emit(DeepResearchEvent.Completed(state))
    }
    
    /**
     * Clarify with user node.
     * Mirrors Python clarify_with_user() function.
     * 
     * Analyzes if the user's query requires research or clarification.
     * Returns Command.GoTo to continue or Command.End to stop.
     */
    private suspend fun clarifyWithUser(state: AgentState): Command {
        logger.debug { "Running clarify_with_user node" }
        
        // If clarification is disabled, go directly to research brief
        if (!config.enableClarification) {
            return Command.GoTo("write_research_brief")
        }
        
        // Build clarification prompt
        val systemPrompt = ResearchPrompts.clarifyWithUserSystemPrompt()
        val messagesContent = messagesToPromptString(state.messages)
        
        val clarifyPrompt = prompt("deep-research-clarify") {
            system(systemPrompt)
            user(messagesContent)
        }
        
        try {
            // Use Koog's executeStructured for type-safe JSON parsing
            val result = promptExecutor.executeStructured(
                prompt = clarifyPrompt,
                model = config.clarifyModel,
                serializer = serializer<ClarificationDecision>()
            )
            
            val clarification = result.getOrThrow().data
            
            when (clarification.decision.lowercase()) {
                "clarify" -> {
                    // User needs clarification
                    state.clarification = clarification.message
                    state.messages.add(Message.AI(content = clarification.message))
                    return Command.End(mapOf("final_report" to clarification.message))
                }
                "no_research" -> {
                    // User's query doesn't require research
                    state.messages.add(Message.AI(content = clarification.message))
                    return Command.End(mapOf("final_report" to clarification.message))
                }
                "research", "proceed" -> {
                    // Proceed with research
                    return Command.GoTo("write_research_brief")
                }
                else -> {
                    // Default: proceed with research
                    return Command.GoTo("write_research_brief")
                }
            }
            
        } catch (e: Exception) {
            logger.warn(e) { "Clarification failed, proceeding with research" }
            return Command.GoTo("write_research_brief")
        }
    }
    
    /**
     * Write research brief node.
     * Mirrors Python write_research_brief() function.
     * 
     * Transforms the user's query into a structured research brief
     * that the supervisor can use to coordinate research.
     */
    private suspend fun writeResearchBrief(state: AgentState) {
        logger.debug { "Running write_research_brief node" }
        
        val systemPrompt = ResearchPrompts.writeResearchBriefSystemPrompt(config.mcpPrompt)
        val messagesContent = messagesToPromptString(state.messages)
        
        val briefPrompt = prompt("deep-research-brief") {
            system(systemPrompt)
            user(messagesContent)
        }
        
        try {
            // Use Koog's executeStructured to get structured research brief with questions
            val result = promptExecutor.executeStructured(
                prompt = briefPrompt,
                model = config.clarifyModel,
                serializer = serializer<ResearchBriefWithQuestions>()
            )
            
            val briefData = result.getOrThrow().data
            
            // Store research brief
            state.researchBrief = briefData.brief
            
            // Add as system message for supervisor
            state.messages.add(Message.System(briefData.brief))
            
            // Add parsed research questions
            for (question in briefData.questions) {
                if (question.isNotBlank()) {
                    state.researchQuestions.add(ResearchQuestion(researchBrief = question))
                }
            }
            
            // If no questions were parsed from structured response, use fallback regex parsing
            if (state.researchQuestions.isEmpty()) {
                val fallbackQuestions = parseResearchQuestions(briefData.brief)
                state.researchQuestions.addAll(fallbackQuestions)
            }
            
            logger.info { "Research brief created with ${state.researchQuestions.size} research questions" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse structured research brief, using raw response" }
            
            // Fallback: use AIAgent if structured output fails
            val agent = AIAgent(
                promptExecutor = promptExecutor,
                llmModel = config.clarifyModel,
                systemPrompt = systemPrompt,
                toolRegistry = ToolRegistry.EMPTY,
                temperature = config.temperature,
                maxIterations = 100
            )
            
            val response = agent.run(messagesContent)
            state.researchBrief = response
            state.messages.add(Message.System(response))
            
            // Parse research questions from response
            val questions = parseResearchQuestions(response)
            state.researchQuestions.addAll(questions)
            
            logger.info { "Research brief created with ${state.researchQuestions.size} research questions (fallback)" }
        }
    }
    
    /**
     * Research supervisor node - invokes the supervisor subgraph.
     * Mirrors Python: supervisor_subgraph.ainvoke({...})
     */
    private suspend fun researchSupervisor(state: AgentState) {
        logger.debug { "Running research_supervisor node (supervisor subgraph)" }
        
        // Initialize supervisor state from agent state
        val supervisorState = SupervisorState(
            researchBrief = state.researchBrief,
            searchToolDescription = getSearchToolDescription(config.searchApi)
        )
        
        // Add system message with research brief
        supervisorState.supervisorMessages.add(Message.System(state.researchBrief))
        
        // Add human message to kick off research
        supervisorState.supervisorMessages.add(Message.Human(
            "Please begin the research based on the brief above. Delegate specific research tasks to your assistants."
        ))
        
        // Run supervisor subgraph
        val result = supervisorSubgraph.invoke(supervisorState)
        
        // Transfer results back to main state
        state.summaries.addAll(result.summaries)
        state.rawNotes.addAll(result.rawNotes)
        
        // Add supervisor messages to main state
        for (msg in result.supervisorMessages) {
            state.messages.add(msg)
        }
        
        logger.info { "Supervisor completed with ${state.summaries.size} research summaries" }
    }
    
    /**
     * Final report generation node.
     * Mirrors Python final_report_generation() function.
     * 
     * Synthesizes all research findings into a comprehensive final report.
     */
    private suspend fun finalReportGeneration(state: AgentState) {
        logger.debug { "Running final_report_generation node" }
        
        // Build context from summaries
        val summariesContext = state.summaries
            .mapIndexed { index, summary -> 
                "## Research Finding ${index + 1}\n${summary.summary}"
            }
            .joinToString("\n\n---\n\n")
        
        // Prepare messages for final report
        val reportMessages = mutableListOf<Message>()
        
        // System prompt
        reportMessages.add(Message.System(ResearchPrompts.finalReportSystemPrompt(config.mcpPrompt)))
        
        // Add the original query
        val originalQuery = state.messages.firstOrNull { it is Message.Human }?.content ?: ""
        reportMessages.add(Message.Human("""
            |Original Query: $originalQuery
            |
            |Research Brief:
            |${state.researchBrief}
            |
            |Research Findings:
            |$summariesContext
            |
            |Please synthesize these findings into a comprehensive final report.
        """.trimMargin()))
        
        val systemPrompt = ResearchPrompts.finalReportSystemPrompt(config.mcpPrompt)
        
        var attempts = 0
        val maxAttempts = 3
        var currentMessages = reportMessages.toMutableList()
        
        while (attempts < maxAttempts) {
            try {
                val agent = AIAgent(
                    promptExecutor = promptExecutor,
                    llmModel = config.finalReportModel,
                    systemPrompt = systemPrompt,
                    toolRegistry = ToolRegistry.EMPTY,
                    temperature = config.temperature,
                    maxIterations = 100
                )
                
                val messagesContent = messagesToPromptString(currentMessages)
                val response = agent.run(messagesContent)
                
                state.finalReport = response
                state.messages.add(Message.AI(response))
                
                return
                
            } catch (e: Exception) {
                attempts++
                
                if (isTokenLimitExceeded(e, config.finalReportModel)) {
                    // Reduce context
                    currentMessages = removeUpToLastAIMessage(currentMessages)
                    continue
                }
                
                logger.error(e) { "Final report attempt $attempts failed" }
                
                if (attempts >= maxAttempts) {
                    state.finalReport = """
                        |# Research Report
                        |
                        |An error occurred while generating the final report.
                        |
                        |## Collected Research Summaries
                        |$summariesContext
                        |
                        |---
                        |Error: ${e.message}
                    """.trimMargin()
                }
            }
        }
    }
    
    /**
     * Parse research questions from the research brief.
     */
    private fun parseResearchQuestions(brief: String): List<ResearchQuestion> {
        val questions = mutableListOf<ResearchQuestion>()
        
        // Look for numbered questions or bullet points
        val questionPatterns = listOf(
            """\d+\.\s*(.+?)(?=\d+\.|$)""".toRegex(RegexOption.DOT_MATCHES_ALL),
            """-\s*(.+?)(?=-\s|$)""".toRegex(RegexOption.DOT_MATCHES_ALL),
            """\*\s*(.+?)(?=\*\s|$)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        )
        
        for (pattern in questionPatterns) {
            val matches = pattern.findAll(brief)
            for (match in matches) {
                val questionText = match.groupValues[1].trim()
                if (questionText.length > 10 && questionText.contains("?")) {
                    questions.add(ResearchQuestion(researchBrief = questionText))
                }
            }
        }
        
        // If no structured questions found, treat the whole brief as one research topic
        if (questions.isEmpty()) {
            questions.add(ResearchQuestion(researchBrief = brief.take(500)))
        }
        
        return questions
    }
}

/**
 * Events emitted during deep research for progress tracking.
 */
sealed class DeepResearchEvent {
    data class Started(val query: String) : DeepResearchEvent()
    data class Phase(val name: String, val description: String) : DeepResearchEvent()
    data class ResearchBrief(val brief: String) : DeepResearchEvent()
    data class ResearchUpdate(val taskNumber: Int, val summary: String) : DeepResearchEvent()
    data class Completed(val state: AgentState) : DeepResearchEvent()
    data class Error(val message: String, val exception: Exception?) : DeepResearchEvent()
}
