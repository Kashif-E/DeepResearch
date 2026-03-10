package ai.kash.delve.state

data class ResearchQuestion(val researchBrief: String)

data class Summary(
    val summary: String,
    val rawNotes: List<String> = emptyList()
)

data class AgentState(
    val messages: MutableList<String> = mutableListOf(),
    var researchBrief: String = "",
    val researchQuestions: MutableList<ResearchQuestion> = mutableListOf(),
    val summaries: MutableList<Summary> = mutableListOf(),
    val rawNotes: MutableList<String> = mutableListOf(),
    val clarificationHistory: MutableList<String> = mutableListOf(),
    val conversationLog: MutableList<String> = mutableListOf(),
    var finalReport: String = ""
)
