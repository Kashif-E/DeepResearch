package ai.kash.delve.memory

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Custom serializer that gracefully handles unknown category values from the LLM
 * by defaulting to [FactCategory.finding] instead of throwing SerializationException.
 */
object FactCategorySerializer : KSerializer<FactCategory> {
    override val descriptor = PrimitiveSerialDescriptor("FactCategory", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: FactCategory) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): FactCategory {
        val raw = decoder.decodeString().lowercase()
        return try { FactCategory.valueOf(raw) } catch (_: Exception) { FactCategory.finding }
    }
}

@Serializable(with = FactCategorySerializer::class)
enum class FactCategory {
    statistic, finding, claim, definition, comparison, recommendation
}

@Serializable
data class ResearchFact(
    val content: String,
    val category: FactCategory = FactCategory.finding,
    val sources: List<String> = emptyList()
)

@Serializable
data class SessionRecord(
    val id: String,
    val query: String,
    val timestamp: String,
    val model: String,
    val facts: List<ResearchFact> = emptyList(),
    val reportPath: String = "",
    val conversationTurns: List<SerializableConversationTurn> = emptyList()
)

@Serializable
data class SessionIndexEntry(
    val id: String,
    val query: String,
    val timestamp: String,
    val factCount: Int
)

@Serializable
data class SessionIndex(
    val sessions: MutableList<SessionIndexEntry> = mutableListOf()
)

@Serializable
enum class FollowUpAction {
    synthesize, deeper, rewrite, research
}

/**
 * Serializable version for disk persistence. The in-memory [ConversationTurn]
 * uses the enum directly; this version uses strings for forward compatibility.
 */
@Serializable
data class SerializableConversationTurn(
    val userMessage: String,
    val response: String,
    val action: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ConversationTurn(
    val userMessage: String,
    val response: String,
    val action: FollowUpAction,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toSerializable() = SerializableConversationTurn(
        userMessage = userMessage,
        response = response,
        action = action.name,
        timestamp = timestamp
    )
}

@Serializable
data class FollowUpDecision(
    val action: String,
    val focus: String = "",
    val refinedQuery: String = ""
)

@Serializable
data class ExtractedFacts(
    val facts: List<ResearchFact>
)
