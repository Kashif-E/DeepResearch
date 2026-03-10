package ai.kash.delve.model

import kotlinx.serialization.Serializable

@Serializable
data class ClarificationDecision(
    val decision: String,
    val message: String = ""
)

@Serializable
data class ResearchBriefWithQuestions(
    val brief: String,
    val questions: List<String> = emptyList()
)

@Serializable
data class ResearchSummary(
    val summary: String,
    val keyExcerpts: String? = null
)

@Serializable
data class FinalReport(
    val report: String,
    val sources: List<String> = emptyList(),
    val researchBrief: String
)

@Serializable
data class MemorySufficiencyDecision(
    val sufficient: Boolean,
    val confidence: String = "low",
    val verificationQuery: String? = null,
    val reasoning: String = ""
)
