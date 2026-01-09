package ai.koog.deepresearch.examples

import ai.koog.deepresearch.DeepResearchAgent
import ai.koog.deepresearch.config.DeepResearchConfig
import ai.koog.deepresearch.config.SearchAPI
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OllamaModels
import kotlinx.coroutines.runBlocking

/**
 * Example of running Koog Deep Research with Ollama (local LLM).
 * 
 * Prerequisites:
 * 1. Install Ollama: https://ollama.ai
 * 2. Start Ollama server: `ollama serve`
 * 3. Pull a model: `ollama pull llama3.2` or `ollama pull qwen2.5:14b`
 * 4. Set TAVILY_API_KEY for web search (optional but recommended)
 * 
 * Run with:
 *   ./gradlew :koog-deep-research:run -PmainClass=ai.koog.deepresearch.examples.OllamaExampleKt
 */
fun main() = runBlocking {
    println(
        """
        |╔══════════════════════════════════════════════════════════════════════╗
        |║            Koog Deep Research - Ollama Local LLM Example             ║
        |╚══════════════════════════════════════════════════════════════════════╝
    """.trimMargin()
    )

    // Check for Tavily API key (recommended for web search)
    val tavilyKey = System.getenv("TAVILY_API_KEY")
    if (tavilyKey.isNullOrBlank()) {
        println("⚠️  Warning: TAVILY_API_KEY not set. Web search will not work.")
        println("   Set it with: export TAVILY_API_KEY=tvly-your-key")
        println()
    }

    // Create Ollama executor (connects to localhost:11434 by default)
    val executor = simpleOllamaAIExecutor()

    // Choose your model - options:
    // - OllamaModels.Meta.LLAMA_3_2 (default, good balance)
    // - OllamaModels.Meta.LLAMA_3_2_3B (faster, smaller)
    // - OllamaModels.Qwen.QWEN_2_5_14B (better reasoning, needs more RAM)
    // - OllamaModels.Groq.LLAMA_3_GROK_TOOL_USE_8B (optimized for tools)

    val model = LLModel(
        provider = LLMProvider.Ollama,
        id = "llama3.1:8b",
        capabilities = listOf(
            LLMCapability.Temperature,
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.Tools
        ),
        contextLength =8_128,
    )

    println("🦙 Using model: ${model.id}")
    println()

    // Configure Deep Research for Ollama
    val config = DeepResearchConfig(
        // Use the same model for all tasks (you can use different models if available)
        researchModel = model,
        supervisorModel = model,
        clarifyModel = model,
        finalReportModel = model,
        compressionModel = model,
        summarizationModel = model,

        // Use Tavily for web search (Ollama doesn't have native search)
        searchApi = if (tavilyKey.isNullOrBlank()) SearchAPI.NONE else SearchAPI.TAVILY,
        tavilyApiKey = tavilyKey,

        // Settings for local models - higher iterations to handle tool call retries
        maxReactToolCalls = 100,
        maxConcurrentResearchUnits = 10,
        maxResearchLoops = 20,

        // Disable clarification for simpler CLI usage
        enableClarification = true,

        // Lower temperature for more focused responses
        temperature = 0.3,
        maxSupervisorIterations = 20,

    )

    // Create the research agent
    val agent = DeepResearchAgent(config, executor)

    // Example research query
    val query = "Explore the concept of an Asian Federation in depth. Trace its intellectual and political origins: who first proposed the idea, in what historical context, and how it has been discussed across different periods in Asian history. Examine past attempts, alliances, or movements toward Asian unity and analyze why they succeeded or failed.\n" +
        "Compare these efforts with the historical foundations of the European Union, focusing on how Europe moved from centuries of internal wars to economic and political integration after major conflicts. Highlight the role of post-war realities, shared threats, and economic interdependence in shaping the EU.\n" +
        "Analyze the contemporary relevance of an Asian Federation in light of recent global tensions, including U.S. actions in Venezuela and Israeli–Iranian hostilities. Discuss how these events influence Asian strategic thinking and whether they reinforce the case for regional unity.\n" +
        "Finally, critically assess the argument that Asia’s long-term stability and sovereignty depend on cooperation and integration to counter Western dominance, rather than internal rivalries and conflicts. Address political, economic, cultural, and security challenges, as well as realistic pathways toward such unity."

    println("📋 Research Query:")
    println("   $query")
    println()
    println("═".repeat(70))
    println("Starting research... (this may take a few minutes with local models)")
    println("═".repeat(70))
    println()

    try {
        // Run the research
        val result = agent.research(query)

        println()
        println("═".repeat(70))
        println("📄 RESEARCH REPORT")
        println("═".repeat(70))
        println()
        println(result.report)

        if (result.sources.isNotEmpty()) {
            println()
            println("─".repeat(70))
            println("📚 Sources:")
            result.sources.forEachIndexed { index, source ->
                println("  ${index + 1}. $source")
            }
        }

    } catch (e: Exception) {
        println()
        println("❌ Error during research: ${e.message}")
        println()
        println("Troubleshooting:")
        println("  1. Is Ollama running? Start with: ollama serve")
        println("  2. Is the model pulled? Run: ollama pull ${model.id}")
        println("  3. Check Ollama logs for errors")
        e.printStackTrace()
    } finally {
        executor.close()
    }
}
