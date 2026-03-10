package ai.kash.delve.memory

/** Maximum report characters to include in synthesize/rewrite prompts. */
private const val MAX_REPORT_IN_PROMPT = 30_000

/** Maximum report preview for the follow-up router classification. */
private const val ROUTER_REPORT_PREVIEW = 2000

object MemoryPrompts {

    fun extractFactsPrompt(): String = """
You are a research fact extractor. Given a research report and summaries, extract the key facts, statistics, findings, and claims.

You MUST respond with ONLY a JSON object in this exact format, no other text:
{"facts": [{"content": "fact text here", "category": "finding", "sources": ["https://..."]}]}

Categories: statistic, finding, claim, definition, comparison, recommendation

Guidelines:
- Each fact should be a standalone statement that makes sense without context
- Include source URLs when available (extract from citations in the report)
- Prefer specific facts over vague summaries
- Each fact must be at least 20 characters and contain concrete information
- Do NOT include meta-commentary about the report itself (e.g., "In this section...", "The report discusses...")
- Do NOT include placeholder or incomplete statements
- Extract 10-30 facts depending on report length

IMPORTANT: Output ONLY the JSON object. No markdown, no explanation, no text before or after.
""".trimIndent()

    fun followUpRouterPrompt(
        originalQuery: String, currentReport: String, conversationHistory: List<ConversationTurn>
    ): String {
        val historyBlock = if (conversationHistory.isNotEmpty()) {
            val turns = conversationHistory.joinToString("\n") { turn ->
                "User: ${turn.userMessage}\nAssistant [${turn.action}]: ${turn.response.take(200)}..."
            }
            "\n\nConversation History:\n$turns"
        } else ""


        val reportPreview = if (currentReport.length > ROUTER_REPORT_PREVIEW) {
            currentReport.take(ROUTER_REPORT_PREVIEW) + "..."
        } else currentReport

        return """
You are a follow-up classifier for a research assistant. The user has received a research report and is asking a follow-up question.

Original Query: $originalQuery
Report Preview: $reportPreview$historyBlock

Classify the user's follow-up into one of these actions:
- "synthesize": The answer can be derived from existing findings (clarify, explain, summarize differently, answer a question about the report)
- "deeper": The user wants more depth on a specific section or aspect (go deeper, expand on, more details about)
- "rewrite": The user wants the report reformatted or rewritten (shorter, longer, different format, different audience, translate)
- "research": The user is asking about something new that requires fresh web searches

You MUST respond with ONLY a JSON object:
{"action": "synthesize|deeper|rewrite|research", "focus": "what aspect to focus on", "refinedQuery": "refined search query if action is research or deeper"}

IMPORTANT: Output ONLY the JSON object. No markdown, no explanation.
""".trimIndent()
    }

    fun synthesizePrompt(
        originalQuery: String, report: String, conversationHistory: List<ConversationTurn>
    ): String {
        val historyBlock = if (conversationHistory.isNotEmpty()) {
            conversationHistory.joinToString("\n\n") { turn ->
                "User: ${turn.userMessage}\nAssistant: ${turn.response}"
            }
        } else ""


        val cappedReport = if (report.length > MAX_REPORT_IN_PROMPT) {
            report.take(MAX_REPORT_IN_PROMPT) + "\n\n[Report truncated for context window — ${report.length - MAX_REPORT_IN_PROMPT} characters omitted]"
        } else report

        return """
You are a research assistant. The user previously asked: "$originalQuery"
You produced the following report:

$cappedReport

${if (historyBlock.isNotBlank()) "Previous follow-up conversation:\n$historyBlock\n" else ""}
Answer the user's follow-up question based on the research findings above. Be specific, cite sources from the report where relevant. If the answer isn't in the report, say so clearly.
""".trimIndent()
    }

    fun rewritePrompt(report: String): String {

        val cappedReport = if (report.length > MAX_REPORT_IN_PROMPT) {
            report.take(MAX_REPORT_IN_PROMPT) + "\n\n[Report truncated — ${report.length - MAX_REPORT_IN_PROMPT} characters omitted]"
        } else report

        return """
You are a research report editor. Rewrite the following report according to the user's instructions. Preserve all facts, sources, and citations. Only change the structure, style, length, or format as requested.

Report:
$cappedReport

Follow the user's rewrite instructions below.
""".trimIndent()
    }

    fun formatMemoryContext(facts: List<Pair<String, ResearchFact>>): String {
        if (facts.isEmpty()) return ""
        val grouped = facts.groupBy { it.first }
        return buildString {
            appendLine("=== Relevant findings from previous research sessions ===")
            appendLine("NOTE: These are from PAST sessions, not the current research.")
            for ((sessionQuery, sessionFacts) in grouped) {
                appendLine()
                appendLine("Previous query: \"$sessionQuery\"")
                for ((_, fact) in sessionFacts) {
                    val sourceStr = if (fact.sources.isNotEmpty()) " [${fact.sources.joinToString(", ")}]" else ""
                    appendLine("- ${fact.content}$sourceStr")
                }
            }
            appendLine()
            appendLine("=== End of previous research findings ===")
        }
    }
}
