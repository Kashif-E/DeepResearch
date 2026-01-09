package ai.koog.deepresearch.examples

import ai.koog.deepresearch.DeepResearchAgent
import ai.koog.deepresearch.config.DeepResearchConfig
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import kotlinx.coroutines.runBlocking

/**
 * Simple example demonstrating basic Deep Research usage.
 * 
 * This example shows how to:
 * 1. Configure the research agent
 * 2. Run a research query
 * 3. Access the results
 */
fun main() = runBlocking {
    // Validate environment
    val openAIKey = System.getenv("OPENAI_API_KEY")
        ?: error("Please set OPENAI_API_KEY environment variable")
    
    val tavilyKey = System.getenv("TAVILY_API_KEY")
    if (tavilyKey.isNullOrEmpty()) {
        println("⚠️  Warning: TAVILY_API_KEY not set - search functionality will fail")
        println("   Get a free API key at: https://tavily.com")
        println()
    }
    
    // Configure the agent
    val config = DeepResearchConfig(
        // Limit iterations for faster demo
        maxResearchLoops = 3,
        maxConcurrentResearchUnits = 2,
        maxReactToolCalls = 5,
        
        // Use GPT-4o for best results
        researchModel = OpenAIModels.Chat.GPT4o,
        compressionModel = OpenAIModels.Chat.GPT4o,
        finalReportModel = OpenAIModels.Chat.GPT4o,
        
        // Skip clarification for CLI usage
        enableClarification = false
    )
    
    // Create the executor and agent
    simpleOpenAIExecutor(openAIKey).use { executor ->
        val agent = DeepResearchAgent(config, executor)
        
        // Define research query
        val query = "What are the main differences between Kotlin coroutines and Java virtual threads?"
        
        println("🔬 Koog Deep Research Agent")
        println("=" .repeat(60))
        println()
        println("📝 Query: $query")
        println()
        println("-".repeat(60))
        println("🔄 Starting research...")
        println("-".repeat(60))
        println()
        
        val startTime = System.currentTimeMillis()
        
        try {
            // Execute research
            val report = agent.research(query)
            
            val duration = (System.currentTimeMillis() - startTime) / 1000.0
            
            println()
            println("=" .repeat(60))
            println("📊 RESEARCH REPORT")
            println("=" .repeat(60))
            println()
            println(report.report)
            println()
            println("-".repeat(60))
            println("📚 Sources (${report.sources.size}):")
            report.sources.forEachIndexed { i, source ->
                println("  [${i + 1}] $source")
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
