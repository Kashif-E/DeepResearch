package ai.koog.deepresearch.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet

/**
 * Tools used by the Research Supervisor to delegate work and signal completion.
 * 
 * The supervisor uses these tools to:
 * 1. Delegate specific research topics to researcher sub-agents
 * 2. Signal when research is complete and ready for report generation
 */
@LLMDescription("Tools for the research supervisor to manage research delegation")
class SupervisorTools : ToolSet {
    
    // Callback that will be set by the supervisor agent to handle research delegation
    var onConductResearch: (suspend (String) -> String)? = null
    
    // Callback that will be set to signal research completion
    var onResearchComplete: (() -> Unit)? = null
    
    /**
     * Delegate a research task to a specialized sub-agent.
     * 
     * Each call to this tool spawns a dedicated research agent that will:
     * - Focus exclusively on the given topic
     * - Use search tools to gather information
     * - Return compressed findings
     * 
     * @param researchTopic The topic to research. Should be detailed (at least a paragraph).
     * @return The compressed research findings from the sub-agent
     */
    @Tool
    @LLMDescription("""
        Delegate a research task to a specialized sub-agent.
        
        WHEN TO USE:
        - When you need to gather information on a specific topic
        - When you want to parallelize research across multiple topics
        - When the main question can be broken into independent subtopics
        
        BEST PRACTICES:
        - Provide detailed, standalone instructions (sub-agents can't see other agents' work)
        - Be very clear and specific - avoid acronyms or abbreviations
        - Each topic should be distinct and non-overlapping
        - Describe the topic in at least a paragraph
        
        EXAMPLES:
        - "Research the history and current market position of Tesla's electric vehicle lineup, including Model S, Model 3, Model X, and Model Y. Focus on sales figures, technological innovations, and competitive positioning against other EV manufacturers."
        - "Investigate OpenAI's approach to AI safety, including their published research papers, safety teams, alignment techniques, and public statements from leadership about responsible AI development."
        
        RETURNS: Compressed research findings from the sub-agent
    """)
    suspend fun conductResearch(
        @LLMDescription("The research task to delegate. Should be a single topic described in high detail (at least a paragraph). Be specific and avoid acronyms.")
        task: String
    ): String {
        return onConductResearch?.invoke(task) 
            ?: "Error: Research delegation not configured"
    }
    
    /**
     * Signal that research is complete and ready for final report generation.
     * 
     * Call this tool when you are satisfied with the research findings and
     * believe you have gathered enough information to answer the user's question.
     * 
     * @return Confirmation that research phase is complete
     */
    @Tool
    @LLMDescription("""
        Signal that research is complete and ready for final report generation.
        
        WHEN TO USE:
        - When you have gathered enough information to comprehensively answer the question
        - When additional research would not significantly improve the answer
        - When you have reached the iteration limit
        
        BEFORE CALLING:
        - Use the think tool to verify you have enough information
        - Ensure all critical aspects of the question have been researched
        
        This will end the research phase and proceed to report generation.
    """)
    fun researchComplete(): String {
        onResearchComplete?.invoke()
        return "Research complete. Proceeding to final report generation."
    }
}
