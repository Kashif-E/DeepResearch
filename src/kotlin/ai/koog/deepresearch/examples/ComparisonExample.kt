package ai.koog.deepresearch.examples

import ai.koog.deepresearch.DeepResearchAgent
import ai.koog.deepresearch.config.DeepResearchConfig
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import kotlinx.coroutines.runBlocking

/**
 * Example demonstrating comparison research.
 * 
 * This example shows how the agent handles comparative analysis
 * by delegating to multiple researcher agents in parallel.
 */
fun main() = runBlocking {
    val openAIKey = System.getenv("OPENAI_API_KEY")
        ?: error("Please set OPENAI_API_KEY environment variable")
    
    // Configure for comparison research
    val config = DeepResearchConfig(
        // Allow more parallel researchers for comparisons
        maxConcurrentResearchUnits = 4,
        maxResearchLoops = 4,
        maxReactToolCalls = 6,
        
        // Use best models
        researchModel = OpenAIModels.Chat.GPT4o,
        finalReportModel = OpenAIModels.Chat.GPT4o,
        
        enableClarification = false
    )
    
    simpleOpenAIExecutor(openAIKey).use { executor ->
        val agent = DeepResearchAgent(config, executor)
        
        // Comparison query - will trigger parallel research
        val query = """
            Compare the web frameworks Spring Boot (Kotlin), Ktor, and Quarkus 
            for building microservices in 2025. 
            
            Focus on:
            - Performance benchmarks
            - Developer experience
            - Ecosystem and community
            - Production readiness
            - Native compilation support
        """.trimIndent()
        
        println("🔬 Koog Deep Research - Comparison Example")
        println("=" .repeat(60))
        println()
        println("📝 Query:")
        println(query)
        println()
        println("-".repeat(60))
        println("🔄 Starting comparison research...")
        println("   This may take a few minutes as multiple topics are researched in parallel.")
        println("-".repeat(60))
        println()
        
        val startTime = System.currentTimeMillis()
        
        try {
            val report = agent.research(query)
            val duration = (System.currentTimeMillis() - startTime) / 1000.0
            
            println()
            println("=" .repeat(60))
            println("📊 COMPARISON REPORT")
            println("=" .repeat(60))
            println()
            println(report.report)
            println()
            println("-".repeat(60))
            println("📚 Sources (${report.sources.size}):")
            report.sources.take(10).forEachIndexed { i, source ->
                println("  [${i + 1}] $source")
            }
            if (report.sources.size > 10) {
                println("  ... and ${report.sources.size - 10} more")
            }
            println("-".repeat(60))
            println()
            println("⏱️  Completed in ${String.format("%.1f", duration)} seconds")
            
        } catch (e: Exception) {
            println("❌ Research failed: ${e.message}")
            e.printStackTrace()
        }
    }
}
