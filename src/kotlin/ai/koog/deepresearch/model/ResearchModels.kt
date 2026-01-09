package ai.koog.deepresearch.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Structured response for clarification decisions.
 * Used with Koog's executeStructured for type-safe parsing.
 */
@Serializable
data class ClarificationDecision(
    /**
     * Decision type: "clarify", "no_research", "research", or "proceed"
     */
    val decision: String,
    
    /**
     * Message to display to user (clarification question or response)
     */
    val message: String = ""
)

/**
 * Result of user clarification analysis.
 */
@Serializable
data class ClarificationResult(
    /**
     * Whether the user needs to be asked a clarifying question.
     */
    val needClarification: Boolean,
    
    /**
     * A question to ask the user to clarify the report scope.
     */
    val question: String,
    
    /**
     * Verification message that we will start research after clarification.
     */
    val verification: String
)

/**
 * Structured research question/brief generated from user input.
 */
@Serializable
data class ResearchBrief(
    /**
     * A detailed research question that will guide the research.
     */
    val researchBrief: String
)

/**
 * Research brief with structured research questions/topics.
 * Used with executeStructured to parse research briefs into questions.
 */
@Serializable
data class ResearchBriefWithQuestions(
    /**
     * The overall research brief/question.
     */
    val brief: String,
    
    /**
     * List of specific research questions to investigate.
     * Each question should be a clear, focused research query.
     */
    val questions: List<String> = emptyList()
)

/**
 * Summary of research findings with key excerpts.
 */
@Serializable
data class ResearchSummary(
    /**
     * Comprehensive summary of findings.
     */
    val summary: String,
    
    /**
     * Key excerpts and quotes from sources.
     */
    val keyExcerpts: String
)

/**
 * Individual search result from web search.
 */
@Serializable
data class SearchResult(
    /**
     * Title of the search result.
     */
    val title: String,
    
    /**
     * URL of the source.
     */
    val url: String,
    
    /**
     * Snippet or description from the search result.
     */
    val snippet: String,
    
    /**
     * Raw content of the page (if available).
     */
    val rawContent: String? = null,
    
    /**
     * Relevance score from the search API.
     */
    val score: Double? = null
)

/**
 * Response from Tavily search API.
 */
@Serializable
data class TavilySearchResponse(
    val query: String,
    val results: List<TavilyResult>
)

@Serializable
data class TavilyResult(
    val title: String,
    val url: String,
    val content: String,
    val rawContent: String? = null,
    val score: Double? = null
)

/**
 * Research topic to be investigated by a researcher agent.
 */
@Serializable
data class ResearchTopic(
    /**
     * The topic to research. Should be a single topic described in high detail.
     */
    val topic: String,
    
    /**
     * Priority level for this research topic.
     */
    val priority: Int = 1
)

/**
 * Compressed research findings from a researcher agent.
 */
@Serializable
data class CompressedResearch(
    /**
     * Compressed and synthesized research findings.
     */
    val compressedResearch: String,
    
    /**
     * Raw notes accumulated during research.
     */
    val rawNotes: List<String> = emptyList()
)

/**
 * Final research report.
 */
@Serializable
data class FinalReport(
    /**
     * The complete research report in markdown format.
     */
    val report: String,
    
    /**
     * List of sources used in the report.
     */
    val sources: List<String> = emptyList(),
    
    /**
     * Research brief that guided the research.
     */
    val researchBrief: String
)

/**
 * State tracking for the research process.
 */
data class ResearchState(
    /**
     * Original user query/message.
     */
    val userQuery: String,
    
    /**
     * Generated research brief.
     */
    val researchBrief: String? = null,
    
    /**
     * Notes collected from all researchers.
     */
    val notes: MutableList<String> = mutableListOf(),
    
    /**
     * Raw notes from tool calls and searches.
     */
    val rawNotes: MutableList<String> = mutableListOf(),
    
    /**
     * Current iteration count for supervisor.
     */
    val researchIterations: Int = 0,
    
    /**
     * Final generated report.
     */
    val finalReport: String? = null
)

/**
 * State for individual researcher agents.
 */
data class ResearcherState(
    /**
     * The specific topic this researcher is investigating.
     */
    val researchTopic: String,
    
    /**
     * Number of tool calls made by this researcher.
     */
    val toolCallIterations: Int = 0,
    
    /**
     * Accumulated research findings.
     */
    val findings: MutableList<String> = mutableListOf(),
    
    /**
     * Raw notes from this researcher.
     */
    val rawNotes: MutableList<String> = mutableListOf()
)

/**
 * Reflection from the think tool.
 */
@Serializable
data class ThinkReflection(
    /**
     * The reflection content.
     */
    val reflection: String
)
