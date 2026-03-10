package ai.kash.delve.memory

import ai.kash.delve.config.DeepResearchConfig
import ai.kash.delve.state.AgentState
import ai.kash.delve.utils.retryWithBackoff
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.structure.executeStructured
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.serializer

private val logger = KotlinLogging.logger {}

class FactExtractor(
    private val config: DeepResearchConfig,
    private val promptExecutor: PromptExecutor
) {

    suspend fun extractFacts(state: AgentState): List<ResearchFact> {
        val report = state.finalReport
        if (report.isBlank()) return emptyList()

        val summariesBlock = if (state.summaries.isNotEmpty()) {
            "\n\n=== Research Summaries ===\n" +
            state.summaries.joinToString("\n---\n") { it.summary }
        } else ""

        val content = "$report$summariesBlock"

        return try {
            withTimeout(60_000) {
                val extractPrompt = prompt("fact-extraction") {
                    system(MemoryPrompts.extractFactsPrompt())
                    user(content)
                }

                val result = retryWithBackoff(maxRetries = 2) {
                    promptExecutor.executeStructured(
                        prompt = extractPrompt,
                        model = config.compressionModel,
                        serializer = serializer<ExtractedFacts>()
                    )
                }
                val raw = result.getOrThrow().data.facts

                val extracted = raw.filter { ResearchMemory.isQualityFact(it) }
                logger.info { "Extracted ${extracted.size} quality facts from research (${raw.size - extracted.size} filtered)" }
                extracted
            }
        } catch (e: Exception) {
            logger.warn(e) { "Fact extraction failed, using fallback chunking" }
            fallbackExtraction(report)
        }
    }

    private fun fallbackExtraction(report: String): List<ResearchFact> {
        val urlPattern = Regex("""https?://[^\s\])"'>]+""")

        return report.split(Regex("\n\\s*\n"))
            .filter { it.length > 50 && !it.startsWith("#") && !it.startsWith(">") }
            .map { paragraph ->
                val sources = urlPattern.findAll(paragraph)
                    .map { it.value.trimEnd('.', ',', ')', ']') }
                    .distinct()
                    .toList()
                ResearchFact(
                    content = paragraph.trim().take(500),
                    category = FactCategory.finding,
                    sources = sources
                )
            }
            .take(30)
    }
}
