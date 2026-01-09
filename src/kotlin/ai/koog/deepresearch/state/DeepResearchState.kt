package ai.koog.deepresearch.state

import kotlinx.serialization.Serializable

/**
 * State definitions that mirror the Python LangGraph implementation exactly.
 * 
 * These state classes follow the same structure as the Python TypedDict/BaseModel classes,
 * with proper state management for the graph-based workflow.
 */

// ==================== Structured Outputs ====================

/**
 * Model for user clarification requests - mirrors Python ClarifyWithUser.
 */
@Serializable
data class ClarifyWithUser(
    /** Whether the user needs to be asked a clarifying question. */
    val needClarification: Boolean,
    /** A question to ask the user to clarify the report scope. */
    val question: String,
    /** Verify message that we will start research after the user has provided the necessary information. */
    val verification: String
)

/**
 * Research question and brief for guiding research - mirrors Python ResearchQuestion.
 */
@Serializable
data class ResearchQuestion(
    /** A research question that will be used to guide the research. */
    val researchBrief: String
)

/**
 * Research summary with key findings - mirrors Python Summary.
 */
@Serializable
data class Summary(
    val summary: String,
    val rawNotes: List<String> = emptyList(),
    val keyExcerpts: String = ""
)

/**
 * Tool call to conduct research on a specific topic - mirrors Python ConductResearch.
 */
@Serializable
data class ConductResearch(
    /** The topic to research. Should be a single topic, described in high detail. */
    val researchTopic: String
)

/**
 * Tool call to indicate research is complete - mirrors Python ResearchComplete.
 */
@Serializable
class ResearchComplete

// ==================== State Definitions ====================

/**
 * Main agent state containing messages and research data.
 * Mirrors Python AgentState(MessagesState).
 */
data class AgentState(
    /** Conversation messages with the user */
    val messages: MutableList<Message> = mutableListOf(),
    /** Messages for the supervisor context */
    var supervisorMessages: MutableList<Message> = mutableListOf(),
    /** Generated research brief */
    var researchBrief: String = "",
    /** Research questions extracted from the brief */
    val researchQuestions: MutableList<ResearchQuestion> = mutableListOf(),
    /** Research summaries from completed research tasks */
    val summaries: MutableList<Summary> = mutableListOf(),
    /** Raw notes accumulated during research */
    val rawNotes: MutableList<String> = mutableListOf(),
    /** Compressed/processed notes */
    val notes: MutableList<String> = mutableListOf(),
    /** Clarification question if needed */
    var clarification: String = "",
    /** Final generated report */
    var finalReport: String = ""
) {
    /** Override reducer for supervisor messages - replaces instead of appending */
    fun overrideSupervisorMessages(newMessages: List<Message>) {
        supervisorMessages = newMessages.toMutableList()
    }
    
    /** Add reducer for notes - appends */
    fun addNotes(newNotes: List<String>) {
        notes.addAll(newNotes)
    }
    
    /** Add reducer for raw notes - appends */
    fun addRawNotes(newNotes: List<String>) {
        rawNotes.addAll(newNotes)
    }
}

/**
 * State for the supervisor that manages research tasks.
 * Mirrors Python SupervisorState(TypedDict).
 */
data class SupervisorState(
    /** Messages in supervisor context */
    val supervisorMessages: MutableList<Message> = mutableListOf(),
    /** Research brief being investigated */
    var researchBrief: String = "",
    /** Research questions extracted from the brief */
    val researchQuestions: MutableList<ResearchQuestion> = mutableListOf(),
    /** Collected research summaries from researchers */
    val summaries: MutableList<Summary> = mutableListOf(),
    /** Collected research notes */
    val notes: MutableList<String> = mutableListOf(),
    /** Number of research iterations completed */
    var researchIterations: Int = 0,
    /** Current iteration count */
    var iterationCount: Int = 0,
    /** Raw notes from tool calls */
    val rawNotes: MutableList<String> = mutableListOf(),
    /** Search tool description from config */
    var searchToolDescription: String = ""
) {
    fun overrideSupervisorMessages(newMessages: List<Message>) {
        supervisorMessages.clear()
        supervisorMessages.addAll(newMessages)
    }
    
    fun addMessages(newMessages: List<Message>) {
        supervisorMessages.addAll(newMessages)
    }
}

/**
 * State for individual researchers conducting research.
 * Mirrors Python ResearcherState(TypedDict).
 */
data class ResearcherState(
    /** Messages in researcher context */
    val researcherMessages: MutableList<Message> = mutableListOf(),
    /** Number of tool call iterations */
    var toolCallIterations: Int = 0,
    /** The specific topic being researched */
    var researchTopic: String = "",
    /** Context from the supervisor for this research task */
    var supervisorContext: String = "",
    /** Compressed research findings */
    var compressedResearch: String = "",
    /** Raw notes from this researcher */
    val rawNotes: MutableList<String> = mutableListOf()
)

/**
 * Output state from individual researchers.
 * Mirrors Python ResearcherOutputState(BaseModel).
 */
@Serializable
data class ResearcherOutputState(
    val compressedResearch: String,
    val rawNotes: List<String> = emptyList()
)

// ==================== Message Types ====================

/**
 * Message types that mirror LangChain message types.
 */
sealed class Message {
    abstract val content: String
    abstract val role: String
    
    data class System(override val content: String) : Message() {
        override val role: String = "system"
    }
    
    data class Human(override val content: String) : Message() {
        override val role: String = "user"
    }
    
    data class AI(
        override val content: String,
        val toolCalls: List<ToolCall> = emptyList()
    ) : Message() {
        override val role: String = "assistant"
    }
    
    data class Tool(
        override val content: String,
        val name: String,
        val toolCallId: String
    ) : Message() {
        override val role: String = "tool"
    }
}

/**
 * Represents a tool call made by the AI.
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val args: Map<String, String>
)

// ==================== Command Type ====================

/**
 * Command returned by nodes to control graph flow.
 * Mirrors Python langgraph Command type.
 * Using Any? for update to allow flexibility like Python's dynamic typing.
 */
sealed class Command {
    /** Go to a specific node */
    data class Goto(
        val node: String,
        val update: Map<String, Any?>? = null
    ) : Command()
    
    /** End the graph execution */
    data class End(
        val update: Map<String, Any?>? = null
    ) : Command()
    
    companion object {
        /** Factory method for Goto - provides GoTo alias */
        fun GoTo(node: String, update: Map<String, Any?>? = null): Command = Goto(node, update)
    }
}

// ==================== Helper Functions ====================

/**
 * Get buffer string from messages - mirrors Python get_buffer_string.
 */
fun getBufferString(messages: List<Message>): String {
    return messages.joinToString("\n\n") { message ->
        "${message.role}: ${message.content}"
    }
}

/**
 * Filter messages by type - mirrors Python filter_messages.
 */
fun filterMessages(
    messages: List<Message>,
    includeTypes: Set<String>
): List<Message> {
    return messages.filter { it.role in includeTypes }
}

/**
 * Get notes from tool call messages.
 */
fun getNotesFromToolCalls(messages: List<Message>): List<String> {
    return messages.filterIsInstance<Message.Tool>().map { it.content }
}

/**
 * Remove messages up to the last AI message - for token limit handling.
 */
fun removeUpToLastAIMessage(messages: MutableList<Message>): MutableList<Message> {
    for (i in messages.lastIndex downTo 0) {
        if (messages[i] is Message.AI) {
            return messages.subList(0, i).toMutableList()
        }
    }
    return messages
}
