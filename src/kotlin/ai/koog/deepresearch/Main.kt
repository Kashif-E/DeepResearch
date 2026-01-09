@file:JvmName("Main")

package ai.koog.deepresearch

import ai.koog.deepresearch.graph.DeepResearchEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

/**
 * Main entry point for running Koog Deep Research from command line.
 * 
 * Usage:
 *   ./gradlew :koog-deep-research:run --args="<your research query>"
 * 
 * Or:
 *   ./gradlew :koog-deep-research:runDeepResearch --args="<your research query>"
 * 
 * Environment variables required:
 *   - OPENAI_API_KEY: Your OpenAI API key
 *   - TAVILY_API_KEY: Your Tavily API key for web search
 * 
 * Example:
 *   export OPENAI_API_KEY=sk-...
 *   export TAVILY_API_KEY=tvly-...
 *   ./gradlew :koog-deep-research:run --args="What are the latest advances in quantum computing?"
 */
fun main(args: Array<String>) = runBlocking {
    // Parse command line arguments
    val query = if (args.isNotEmpty()) {
        args.joinToString(" ")
    } else {
        println("""
            |╔══════════════════════════════════════════════════════════════════╗
            |║                     Koog Deep Research Agent                      ║
            |║                                                                   ║
            |║  A 1:1 port of Open Deep Research (Python/LangGraph) to Kotlin   ║
            |╚══════════════════════════════════════════════════════════════════╝
            |
            |Usage: ./gradlew :koog-deep-research:run --args="<your research query>"
            |
            |Environment variables required:
            |  - OPENAI_API_KEY: Your OpenAI API key
            |  - TAVILY_API_KEY: Your Tavily API key
            |
            |Enter your research query (or 'quit' to exit):
        """.trimMargin())
        
        print("> ")
        val input = readLine()?.trim()
        
        if (input.isNullOrBlank() || input.equals("quit", ignoreCase = true)) {
            println("Exiting.")
            return@runBlocking
        }
        input
    }
    
    // Validate environment
    val openaiKey = System.getenv("OPENAI_API_KEY")
    val tavilyKey = System.getenv("TAVILY_API_KEY")
//
//    if (openaiKey.isNullOrBlank()) {
//        logger.error { "OPENAI_API_KEY environment variable is not set." }
//        println("Error: OPENAI_API_KEY environment variable is required.")
//        return@runBlocking
//    }
    
    if (tavilyKey.isNullOrBlank()) {
        logger.warn { "TAVILY_API_KEY not set. Web search will not work." }
        println("Warning: TAVILY_API_KEY not set. Web search functionality will be limited.")
    }
    
    println()
    println("═".repeat(70))
    println("Starting Deep Research on: ${query.take(60)}...")
    println("═".repeat(70))
    println()
    
    try {
        // Create agent from environment
        val agent = KoogDeepResearch.createFromEnvironment()
        
        // Run research with streaming progress
        agent.researchStream(query)
            .onEach { event ->
                when (event) {
                    is DeepResearchEvent.Started -> {
                        println("🔬 Research started")
                    }
                    is DeepResearchEvent.Phase -> {
                        println("📍 Phase: ${event.name} - ${event.description}")
                    }
                    is DeepResearchEvent.ResearchBrief -> {
                        println()
                        println("📋 Research Brief:")
                        println("─".repeat(50))
                        println(event.brief.take(500))
                        if (event.brief.length > 500) println("...")
                        println("─".repeat(50))
                        println()
                    }
                    is DeepResearchEvent.ResearchUpdate -> {
                        println("📝 Research Task ${event.taskNumber} completed")
                    }
                    is DeepResearchEvent.Completed -> {
                        println()
                        println("═".repeat(70))
                        println("📄 FINAL REPORT")
                        println("═".repeat(70))
                        println()
                        println(event.state.finalReport)
                        println()
                        println("═".repeat(70))
                        println("Research completed successfully!")
                        println("  - Summaries collected: ${event.state.summaries.size}")
                        println("  - Report length: ${event.state.finalReport.length} characters")
                        println("═".repeat(70))
                    }
                    is DeepResearchEvent.Error -> {
                        println("❌ Error: ${event.message}")
                        event.exception?.let { e ->
                            logger.error(e) { "Research error" }
                        }
                    }
                }
            }
            .collect()
        
    } catch (e: Exception) {
        logger.error(e) { "Research failed" }
        println()
        println("❌ Research failed: ${e.message}")
        e.printStackTrace()
    }
}

/**
 * Interactive mode for continuous research queries.
 */
suspend fun interactiveMode() {
    println("""
        |╔══════════════════════════════════════════════════════════════════╗
        |║                 Koog Deep Research - Interactive Mode            ║
        |╚══════════════════════════════════════════════════════════════════╝
        |
        |Commands:
        |  - Type your research query and press Enter
        |  - Type 'quit' or 'exit' to stop
        |  - Type 'config' to see current configuration
        |
    """.trimMargin())
    
    val agent = KoogDeepResearch.createFromEnvironment()
    
    while (true) {
        print("\n> ")
        val input = readLine()?.trim()
        
        when {
            input.isNullOrBlank() -> continue
            input.equals("quit", ignoreCase = true) || input.equals("exit", ignoreCase = true) -> {
                println("Goodbye!")
                break
            }
            input.equals("config", ignoreCase = true) -> {
                val config = agent.getConfig()
                println("""
                    |Current Configuration:
                    |  - Clarify Model: ${config.clarifyModel.id}
                    |  - Supervisor Model: ${config.supervisorModel.id}
                    |  - Research Model: ${config.researchModel.id}
                    |  - Final Report Model: ${config.finalReportModel.id}
                    |  - Max Research Loops: ${config.maxResearchLoops}
                    |  - Max Tool Calls: ${config.maxReactToolCalls}
                    |  - Tavily API: ${if (config.tavilyApiKey.isNullOrBlank()) "Not set" else "Configured"}
                """.trimMargin())
            }
            else -> {
                println("\nResearching: ${input.take(60)}...")
                try {
                    val result = agent.researchAsync(input)
                    println("\n" + "═".repeat(70))
                    println("FINAL REPORT")
                    println("═".repeat(70))
                    println(result.finalReport)
                } catch (e: Exception) {
                    println("Error: ${e.message}")
                }
            }
        }
    }
}



