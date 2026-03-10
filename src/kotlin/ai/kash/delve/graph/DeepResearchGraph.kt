package ai.kash.delve.graph

import ai.kash.delve.config.DeepResearchConfig
import ai.kash.delve.model.ClarificationDecision
import ai.kash.delve.model.MemorySufficiencyDecision
import ai.kash.delve.model.ResearchBriefWithQuestions
import ai.kash.delve.prompts.ResearchPrompts
import ai.kash.delve.rag.DocumentRAG
import ai.kash.delve.state.*
import ai.kash.delve.utils.*
import ai.kash.delve.utils.SearchProgressCallbacks
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.structure.executeStructured
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.serializer

private val logger = KotlinLogging.logger {}

private sealed class ClarifyResult {
    data object Proceed : ClarifyResult()
    data class Stop(val response: String) : ClarifyResult()
}

private sealed class MemorySufficiency {
    data object Insufficient : MemorySufficiency()
    data object SufficientNoSearch : MemorySufficiency()
    data class SufficientWithVerification(val verificationQuery: String) : MemorySufficiency()
}

// Pipeline: clarify -> brief -> supervisor -> report
class DeepResearchGraph(
    private val config: DeepResearchConfig,
    private val promptExecutor: PromptExecutor,
    private val rag: DocumentRAG? = null,
    private val memoryContext: String = ""
) {

    suspend fun invoke(query: String): AgentState {
        val state = AgentState()
        state.messages.add(query)

        state.conversationLog.add("[User Query] $query")

        logger.info { "Step 1/4: Clarifying user request..." }
        val clarifyResult = clarifyWithUser(query)

        if (clarifyResult is ClarifyResult.Stop) {
            state.clarificationHistory.add("User: $query")
            state.clarificationHistory.add("Assistant: ${clarifyResult.response}")
            state.conversationLog.add("[Clarification] ${clarifyResult.response}")
            state.finalReport = clarifyResult.response
            return state
        }

        state.conversationLog.add("[Clarification] Proceeding with research")

        logger.info { "Step 2/4: Writing research brief..." }
        writeResearchBrief(state)
        state.conversationLog.add("[Research Brief] ${state.researchBrief}")
        state.conversationLog.add("[Research Questions] ${state.researchQuestions.joinToString("; ") { it.researchBrief }}")

        val memorySufficiency = checkMemorySufficiency(state)

        when (memorySufficiency) {
            is MemorySufficiency.SufficientNoSearch -> {
                logger.info { "Memory sufficient — skipping research, generating report from past facts" }
                state.conversationLog.add("[Memory] Sufficient to answer query, skipping research")
            }
            is MemorySufficiency.SufficientWithVerification -> {
                logger.info { "Memory mostly sufficient — running single verification search" }
                state.conversationLog.add("[Memory] Running single verification search: ${memorySufficiency.verificationQuery}")
                runVerificationSearch(state, memorySufficiency.verificationQuery)
            }
            is MemorySufficiency.Insufficient -> {
                logger.info { "Step 3/4: Conducting research via supervisor..." }
                researchSupervisor(state)
                state.conversationLog.add("[Supervisor] Completed ${state.summaries.size} research tasks")
            }
        }

        logger.info { "Step ${if (memorySufficiency is MemorySufficiency.Insufficient) "4/4" else "3/3"}: Generating final report..." }
        finalReportGeneration(state)

        logger.info { "Research complete! Report length: ${state.finalReport.length} characters" }
        return state
    }

    fun stream(query: String): Flow<DeepResearchEvent> = kotlinx.coroutines.flow.channelFlow {
        send(DeepResearchEvent.Started(query))

        val state = AgentState()
        state.messages.add(query)

        val seenUrls = mutableSetOf<String>()
        val callbacks = SearchProgressCallbacks(
            onSearchQuery = { q -> send(DeepResearchEvent.SourceSearch(q)) },
            onSourceFound = { url, title ->
                if (seenUrls.add(url)) {
                    send(DeepResearchEvent.SourceFound(url, title))
                }
            }
        )

        try {
            send(DeepResearchEvent.Phase("clarify_with_user", "Analyzing your query..."))
            val clarifyResult = clarifyWithUser(query)

            if (clarifyResult is ClarifyResult.Stop) {
                state.clarificationHistory.add("User: $query")
                state.clarificationHistory.add("Assistant: ${clarifyResult.response}")
                send(DeepResearchEvent.Clarification(clarifyResult.response))
                return@channelFlow
            }

            send(DeepResearchEvent.Phase("write_research_brief", "Creating research plan..."))
            writeResearchBrief(state)
            send(DeepResearchEvent.ResearchBrief(state.researchBrief))

            val memorySufficiency = checkMemorySufficiency(state)

            when (memorySufficiency) {
                is MemorySufficiency.SufficientNoSearch -> {
                    send(DeepResearchEvent.Phase("memory_sufficient", "Answering from past research..."))
                }
                is MemorySufficiency.SufficientWithVerification -> {
                    send(DeepResearchEvent.Phase("memory_verification", "Verifying with a quick search..."))
                    runVerificationSearch(state, memorySufficiency.verificationQuery, callbacks)
                }
                is MemorySufficiency.Insufficient -> {
                    send(DeepResearchEvent.Phase("research_supervisor", "Conducting research..."))
                    researchSupervisor(state, callbacks) { taskNumber, summary ->
                        send(DeepResearchEvent.ResearchUpdate(taskNumber, summary))
                    }
                }
            }

            send(DeepResearchEvent.Phase("final_report_generation", "Generating final report..."))
            finalReportGeneration(state)

            send(DeepResearchEvent.Completed(state))
        } catch (e: Exception) {
            logger.error(e) { "Research pipeline error" }
            send(DeepResearchEvent.Error("Research failed: ${e.message}", e))
        }
    }

    private suspend fun clarifyWithUser(query: String): ClarifyResult {
        if (!config.enableClarification) return ClarifyResult.Proceed

        val clarifyPrompt = prompt("deep-research-clarify") {
            system(ResearchPrompts.clarifyWithUserSystemPrompt())
            user(query)
        }

        return try {
            val result = retryWithBackoff {
                promptExecutor.executeStructured(
                    prompt = clarifyPrompt,
                    model = config.clarifyModel,
                    serializer = serializer<ClarificationDecision>()
                )
            }
            val clarification = result.getOrThrow().data

            when (clarification.decision.lowercase()) {
                "clarify", "no_research" -> ClarifyResult.Stop(clarification.message)
                else -> ClarifyResult.Proceed
            }
        } catch (e: Exception) {
            logger.warn(e) { "Clarification failed, proceeding with research" }
            ClarifyResult.Proceed
        }
    }

    private suspend fun writeResearchBrief(state: AgentState) {
        val systemPrompt = ResearchPrompts.writeResearchBriefSystemPrompt(config.mcpPrompt)
        val userQuery = state.messages.firstOrNull() ?: ""

        val clarificationContext = if (state.clarificationHistory.isNotEmpty()) {
            "\n\nClarification Context:\n" + state.clarificationHistory.joinToString("\n")
        } else ""

        val memoryBlock = if (memoryContext.isNotBlank()) "\n\n$memoryContext" else ""

        val briefPrompt = prompt("deep-research-brief") {
            system(systemPrompt)
            user("$userQuery$clarificationContext$memoryBlock")
        }

        repeat(config.maxStructuredOutputRetries) { attempt ->
            try {
                val result = retryWithBackoff {
                    promptExecutor.executeStructured(
                        prompt = briefPrompt,
                        model = config.clarifyModel,
                        serializer = serializer<ResearchBriefWithQuestions>()
                    )
                }
                val briefData = result.getOrThrow().data
                state.researchBrief = briefData.brief

                for (question in briefData.questions) {
                    if (question.isNotBlank()) {
                        state.researchQuestions.add(ResearchQuestion(researchBrief = question))
                    }
                }

                if (state.researchQuestions.isNotEmpty()) {
                    logger.info { "Research brief created with ${state.researchQuestions.size} questions" }
                    return
                }

                logger.warn { "Structured output returned empty questions (attempt ${attempt + 1})" }
            } catch (e: Exception) {
                logger.warn(e) { "Structured brief attempt ${attempt + 1} failed" }
            }
        }
        // All retries exhausted — use direct prompt as fallback
        if (state.researchBrief.isBlank()) {
            logger.warn { "All structured attempts failed, using direct prompt" }
            val fallbackPrompt = prompt("deep-research-brief-fallback") {
                system(systemPrompt)
                user(state.messages.firstOrNull() ?: "")
            }
            val response = withTimeout(60_000) {
                promptExecutor.execute(fallbackPrompt, config.clarifyModel)
            }
            state.researchBrief = response.first().content
        }

        if (state.researchQuestions.isEmpty() && state.researchBrief.isNotBlank()) {
            state.researchQuestions.add(ResearchQuestion(researchBrief = state.researchBrief.take(500)))
        }

        logger.info { "Research brief created with ${state.researchQuestions.size} questions (fallback)" }
    }

    private suspend fun checkMemorySufficiency(state: AgentState): MemorySufficiency {
        if (memoryContext.isBlank()) return MemorySufficiency.Insufficient

        val userQuery = state.messages.firstOrNull() ?: return MemorySufficiency.Insufficient

        val sufficiencyPrompt = prompt("memory-sufficiency-check") {
            system(ResearchPrompts.memorySufficiencyPrompt())
            user(buildString {
                appendLine("User Query: $userQuery")
                appendLine()
                appendLine("Research Brief: ${state.researchBrief}")
                appendLine()
                appendLine("Past Research Facts:")
                append(memoryContext)
            })
        }

        return try {
            val result = retryWithBackoff {
                promptExecutor.executeStructured(
                    prompt = sufficiencyPrompt,
                    model = config.clarifyModel,
                    serializer = serializer<MemorySufficiencyDecision>()
                )
            }
            val decision = result.getOrThrow().data
            logger.info { "Memory sufficiency: sufficient=${decision.sufficient}, confidence=${decision.confidence}, reasoning=${decision.reasoning}" }

            when {
                decision.sufficient && decision.confidence == "high" -> MemorySufficiency.SufficientNoSearch
                decision.sufficient && !decision.verificationQuery.isNullOrBlank() -> MemorySufficiency.SufficientWithVerification(decision.verificationQuery)
                decision.sufficient -> MemorySufficiency.SufficientNoSearch
                else -> MemorySufficiency.Insufficient
            }
        } catch (e: Exception) {
            logger.warn(e) { "Memory sufficiency check failed, proceeding with full research" }
            MemorySufficiency.Insufficient
        }
    }

    private suspend fun runVerificationSearch(
        state: AgentState,
        verificationQuery: String,
        callbacks: SearchProgressCallbacks? = null
    ) {
        try {
            logger.info { "Running verification search: $verificationQuery" }
            val result = tavilySearch(
                listOf(verificationQuery), config,
                summarizationExecutor = promptExecutor,
                summarizationModel = config.summarizationModel,
                callbacks = callbacks
            )
            if (result.isNotBlank()) {
                state.summaries.add(Summary(
                    summary = "Verification search for: $verificationQuery\n\n$result",
                    rawNotes = listOf(result)
                ))
                state.rawNotes.add(result)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Verification search failed: ${e.message}" }
        }
    }

    private suspend fun researchSupervisor(
        state: AgentState,
        callbacks: SearchProgressCallbacks? = null,
        onResearchUpdate: (suspend (Int, String) -> Unit)? = null
    ) {
        val contextualBrief = buildString {
            append(state.researchBrief)
            if (state.clarificationHistory.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("Clarification Context:")
                state.clarificationHistory.forEach { appendLine(it) }
            }
            if (memoryContext.isNotBlank()) {
                appendLine()
                appendLine()
                append(memoryContext)
            }
        }

        val output = runSupervisor(
            researchBrief = contextualBrief,
            config = config,
            promptExecutor = promptExecutor,
            rag = rag,
            onResearchComplete = onResearchUpdate,
            researchQuestions = state.researchQuestions,
            callbacks = callbacks
        )

        state.summaries.addAll(output.summaries)
        state.rawNotes.addAll(output.rawNotes)

        logger.info { "Supervisor completed with ${state.summaries.size} research summaries" }
    }

    private suspend fun finalReportGeneration(state: AgentState) {
        val originalQuery = state.messages.firstOrNull() ?: ""
        val systemPrompt = ResearchPrompts.finalReportSystemPrompt(config.mcpPrompt)

       var includedSummaries = state.summaries.toList()
        var attempts = 0
        val maxAttempts = 3

        while (attempts < maxAttempts) {
            val summariesContext = includedSummaries
                .mapIndexed { index, summary ->
                    "## Research Finding ${index + 1}\n${summary.summary}"
                }
                .joinToString("\n\n---\n\n")

            val clarificationContext = if (state.clarificationHistory.isNotEmpty()) {
                "\n\nClarification Context:\n" + state.clarificationHistory.joinToString("\n")
            } else ""

            val messagesContext = if (state.messages.size > 1) {
                "\n\nConversation History:\n" + state.messages.joinToString("\n")
            } else ""

            val rawNotesContext = if (state.rawNotes.isNotEmpty() && includedSummaries.size <= 2) {
                val notes = state.rawNotes.joinToString("\n---\n").take(config.maxContentLength)
                "\n\nSupplementary Raw Research Notes:\n$notes"
            } else ""

            val memoryBlock = if (memoryContext.isNotBlank()) "\n\n$memoryContext" else ""

            val conversationContext = if (state.conversationLog.isNotEmpty()) {
                "\n\nPipeline Log:\n" + state.conversationLog.joinToString("\n")
            } else ""


            val userContent = """
                |Original Query: $originalQuery$clarificationContext$messagesContext$conversationContext
                |
                |Research Brief:
                |${state.researchBrief}
                |
                |Current Research Findings (from this session):
                |$summariesContext$rawNotesContext
                |$memoryBlock
                |Please synthesize these findings into a comprehensive final report.
                |Prioritize current research findings over previous research when they conflict.
            """.trimMargin()

            try {
                val reportPrompt = prompt("deep-research-report") {
                    system(systemPrompt)
                    user(userContent)
                }

                val response = withTimeout(180_000) {
                    retryWithBackoff {
                        promptExecutor.execute(reportPrompt, config.finalReportModel)
                    }
                }

                state.finalReport = response.first().content
                return

            } catch (e: Exception) {
                attempts++

                if (isTokenLimitExceeded(e) && includedSummaries.size > 1) {
                    includedSummaries = includedSummaries.dropLast(1)
                    logger.warn { "Token limit exceeded, reducing to ${includedSummaries.size} summaries (attempt $attempts)" }
                    continue
                }

                logger.error(e) { "Final report attempt $attempts failed" }

                if (attempts >= maxAttempts) {
                    val fallbackContext = includedSummaries
                        .mapIndexed { i, s -> "## Research Finding ${i + 1}\n${s.summary}" }
                        .joinToString("\n\n---\n\n")
                    state.finalReport = """
                        |# Research Report
                        |
                        |An error occurred while generating the final report.
                        |
                        |## Collected Research Summaries
                        |$fallbackContext
                        |
                        |---
                        |Error: ${e.message}
                    """.trimMargin()
                }
            }
        }
    }
}

sealed class DeepResearchEvent {
    data class Started(val query: String) : DeepResearchEvent()
    data class Phase(val name: String, val description: String) : DeepResearchEvent()
    data class Clarification(val question: String) : DeepResearchEvent()
    data class ResearchBrief(val brief: String) : DeepResearchEvent()
    data class SourceSearch(val query: String) : DeepResearchEvent()
    data class SourceFound(val url: String, val title: String) : DeepResearchEvent()
    data class ResearchUpdate(val taskNumber: Int, val summary: String) : DeepResearchEvent()
    data class Completed(val state: AgentState) : DeepResearchEvent()
    data class Error(val message: String, val exception: Exception) : DeepResearchEvent()
}
