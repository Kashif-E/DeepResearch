package ai.koog.deepresearch.examples

import ai.koog.deepresearch.KoogDeepResearch
import ai.koog.deepresearch.config.SearchAPI
import ai.koog.prompt.executor.clients.google.GoogleModels
import kotlinx.coroutines.runBlocking

/**
 * Example of running Koog Deep Research with Google Gemini models.
 * 
 * Prerequisites:
 * 1. Get a Google AI API key from https://makersuite.google.com/app/apikey
 * 2. Set GOOGLE_API_KEY environment variable
 * 3. Optionally set TAVILY_API_KEY for web search
 * 
 * Run with:
 *   export GOOGLE_API_KEY=your-google-api-key
 *   export TAVILY_API_KEY=tvly-your-tavily-key  # optional
 *   ./gradlew :koog-deep-research:run -PmainClass=ai.koog.deepresearch.examples.GoogleExampleKt
 */
fun main() = runBlocking {
    println("""
        |╔══════════════════════════════════════════════════════════════════════╗
        |║          Koog Deep Research - Google Gemini Example                  ║
        |╚══════════════════════════════════════════════════════════════════════╝
    """.trimMargin())
    
    // Check for Google API key
    val googleApiKey = "AIzaSyBDUJCGhG6VO6gaajIYLnMoMEg__tsHuz0"
    if (googleApiKey.isNullOrBlank()) {
        println("❌ Error: GOOGLE_API_KEY not set")
        println("   Get your key from: https://makersuite.google.com/app/apikey")
        println("   Then run: export GOOGLE_API_KEY=your-key")
        return@runBlocking
    }
    
    // Check for Tavily API key (optional but recommended)
    val tavilyKey = "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"
    if (tavilyKey.isNullOrBlank()) {
        println("⚠️  Warning: TAVILY_API_KEY not set. Web search will not work.")
        println("   Set it with: export TAVILY_API_KEY=tvly-your-key")
        println()
    }
    
    println("🔷 Using Google Gemini models")
    println("   - Gemini 2.5 Pro (supervisor, final report)")
    println("   - Gemini 2.5 Flash (research, summarization)")
    println()
    
    // Create Deep Research agent with Google models
    val agent = KoogDeepResearch.create {
        // Use Google Gemini models
        useGoogleModels()
        
        // Disable clarification for simpler CLI usage
        enableClarification = false
    }
    
    // Example research query
    val query = "What are the key differences between Kotlin and Java for Android development?"
    
    println("📋 Research Query:")
    println("   $query")
    println()
    println("═".repeat(70))
    println("Starting research...")
    println("═".repeat(70))
    println()
    
    try {
        val result = agent.research(query)
        
        println()
        println("═".repeat(70))
        println("📄 RESEARCH REPORT")
        println("═".repeat(70))
        println()
        println(result.finalReport)
        
    } catch (e: Exception) {
        println()
        println("❌ Error during research: ${e.message}")
        e.printStackTrace()
    }
}


