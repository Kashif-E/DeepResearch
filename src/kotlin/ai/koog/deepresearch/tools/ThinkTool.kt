package ai.koog.deepresearch.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet

/**
 * Think tool for strategic reflection and planning.
 * 
 * This tool allows the agent to pause and reflect on its progress,
 * plan next steps, and analyze results without taking any external actions.
 * 
 * The think tool is crucial for:
 * - Planning research strategy before searches
 * - Analyzing search results after each query
 * - Deciding whether to continue or stop researching
 * - Breaking down complex tasks into subtasks
 */
@LLMDescription("Tool for reflection and strategic planning during research")
class ThinkTool : ToolSet {
    
    /**
     * Record a reflection or strategic thought.
     * 
     * Use this tool to pause and think about:
     * - What you've learned so far
     * - What information is still missing
     * - Whether you have enough to answer the question
     * - How to plan your next steps
     * 
     * This helps you make better decisions about when to search more
     * or when to stop and compile your findings.
     * 
     * @param reflection Your reflection, analysis, or strategic thoughts
     * @return Confirmation that the reflection was recorded
     */
    @Tool
    @LLMDescription("""
        Use this tool to think, reflect, and plan your research strategy.
        
        WHEN TO USE:
        - Before searching: Plan your search queries
        - After searching: Analyze what you found and what's missing
        - Before deciding: Consider if you have enough information
        
        WHAT TO REFLECT ON:
        - What key information did I find?
        - What's still missing?
        - Do I have enough to answer comprehensively?
        - Should I search more or stop here?
        
        IMPORTANT: Do not call this tool in parallel with search tools.
        Use it sequentially to reflect on results.
    """)
    fun think(
        @LLMDescription("Your thought, reflection, or strategic planning. Be specific about what you learned and what you need.")
        thought: String
    ): String {
        // The think tool doesn't perform any external action
        // It just allows the agent to record its thoughts in the conversation
        return "Reflection recorded: $thought"
    }
}
