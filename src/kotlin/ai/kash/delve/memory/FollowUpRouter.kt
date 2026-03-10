package ai.kash.delve.memory

import ai.kash.delve.config.DeepResearchConfig
import ai.kash.delve.graph.DeepResearchGraph
import ai.kash.delve.rag.DocumentRAG
import ai.kash.delve.state.AgentState
import ai.kash.delve.utils.retryWithBackoff
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.structure.executeStructured
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.serializer

private val logger = KotlinLogging.logger {}

class FollowUpRouter(
    private val config: DeepResearchConfig,
    private val promptExecutor: PromptExecutor,
    private val rag: DocumentRAG? = null
) {

    suspend fun classify(
        followUp: String,
        originalQuery: String,
        currentReport: String,
        conversationHistory: List<ConversationTurn>
    ): FollowUpDecision {
        val routerPrompt = prompt("follow-up-router") {
            system(MemoryPrompts.followUpRouterPrompt(originalQuery, currentReport, conversationHistory))
            user(followUp)
        }

        return try {
            val result = retryWithBackoff {
                promptExecutor.executeStructured(
                    prompt = routerPrompt,
                    model = config.clarifyModel,
                    serializer = serializer<FollowUpDecision>()
                )
            }
            result.getOrThrow().data
        } catch (e: Exception) {
            logger.warn(e) { "Follow-up classification failed, defaulting to synthesize" }
            FollowUpDecision(action = "synthesize", focus = followUp)
        }
    }

    suspend fun executeSynthesize(
        followUp: String,
        originalQuery: String,
        currentReport: String,
        conversationHistory: List<ConversationTurn>
    ): String {
        val synthPrompt = prompt("follow-up-synthesize") {
            system(MemoryPrompts.synthesizePrompt(originalQuery, currentReport, conversationHistory))
            user(followUp)
        }

        return try {
            withTimeout(120_000) {
                val response = retryWithBackoff {
                    promptExecutor.execute(synthPrompt, config.finalReportModel)
                }
                response.first().content
            }
        } catch (e: Exception) {
            logger.error(e) { "Synthesize failed" }
            "Error generating response: ${e.message}"
        }
    }

    suspend fun executeRewrite(
        followUp: String,
        currentReport: String
    ): String {
        val rewritePrompt = prompt("follow-up-rewrite") {
            system(MemoryPrompts.rewritePrompt(currentReport))
            user(followUp)
        }

        return try {
            withTimeout(180_000) {
                val response = retryWithBackoff {
                    promptExecutor.execute(rewritePrompt, config.finalReportModel)
                }
                response.first().content
            }
        } catch (e: Exception) {
            logger.error(e) { "Rewrite failed" }
            "Error rewriting report: ${e.message}"
        }
    }

    suspend fun executeDeeper(
        focus: String,
        originalQuery: String,
        existingState: AgentState,
        memoryContext: String = ""
    ): AgentState {
        val deeperQuery = buildString {
            appendLine("Original question: $originalQuery")
            appendLine()
            appendLine("Previous research has already covered these findings:")
            for ((i, summary) in existingState.summaries.withIndex()) {
                appendLine("- Finding ${i + 1}: ${summary.summary.take(200)}")
            }
            appendLine()
            appendLine("NOW: Go deeper on this specific aspect: $focus")
            appendLine("Do NOT repeat what was already found. Focus only on new, deeper information about: $focus")
            if (memoryContext.isNotBlank()) {
                appendLine()
                appendLine(memoryContext)
            }
        }


        val followUpConfig = config.copy(enableClarification = false)
        val graph = DeepResearchGraph(followUpConfig, promptExecutor, rag, memoryContext)
        return graph.invoke(deeperQuery)
    }

    suspend fun executeResearch(
        refinedQuery: String,
        memoryContext: String = ""
    ): AgentState {

        val followUpConfig = config.copy(enableClarification = false)
        val graph = DeepResearchGraph(followUpConfig, promptExecutor, rag, memoryContext)
        return graph.invoke(refinedQuery)
    }
}
