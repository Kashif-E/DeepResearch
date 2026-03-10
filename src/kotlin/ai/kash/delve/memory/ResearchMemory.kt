package ai.kash.delve.memory

import ai.kash.delve.rag.DocumentRAG
import ai.kash.delve.rag.TextChunk
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private val memoryJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
    encodeDefaults = true
}

/** Maximum facts to index at startup to keep embedding time bounded. */
private const val MAX_INDEXED_FACTS = 500

/** Minimum similarity score for memory retrieval (0.0 to 1.0). */
private const val MIN_RETRIEVAL_SCORE = 0.3

/** Minimum fact content length to be stored. */
private const val MIN_FACT_LENGTH = 20

/** Patterns that indicate meta-commentary rather than actual facts. */
private val META_PATTERNS = listOf(
    Regex("^(in this|this section|we will|the following|as mentioned|note that)", RegexOption.IGNORE_CASE),
    Regex("^(see also|refer to|for more|click here)", RegexOption.IGNORE_CASE)
)

class ResearchMemory(
    memoryDir: File = File(System.getProperty("user.home"), ".delve/memory"),
    private val ollamaBaseUrl: String = "http://localhost:11434"
) {
    private val sessionsDir = File(memoryDir, "sessions")
    private val indexFile = File(memoryDir, "index.json")
    private val fileMutex = Mutex()
    private var rag: DocumentRAG? = null
    private val allFacts = CopyOnWriteArrayList<IndexedFact>()
    private val factsByContent = ConcurrentHashMap<String, IndexedFact>()

    private val normalizedKeys = ConcurrentHashMap.newKeySet<String>()

    /** Internal representation that includes session timestamp for recency sorting. */
    private data class IndexedFact(
        val query: String,
        val timestamp: String,
        val fact: ResearchFact
    )

    suspend fun initialize(onProgress: ((String) -> Unit)? = null): Int {
        sessionsDir.mkdirs()

        val sessionFiles = sessionsDir.listFiles { f -> f.extension == "json" } ?: emptyArray()
        if (sessionFiles.isEmpty()) {
            logger.info { "No past research sessions found" }
            return 0
        }

        allFacts.clear()
        factsByContent.clear()
        normalizedKeys.clear()


        val sessions = sessionFiles.mapNotNull { file ->
            try {
                memoryJson.decodeFromString<SessionRecord>(file.readText())
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load session: ${file.name}" }
                null
            }
        }.sortedByDescending { it.timestamp }

        var totalFacts = 0
        for (session in sessions) {
            for (fact in session.facts) {
                if (!isQualityFact(fact)) continue
                val normalized = normalizeContent(fact.content)

                if (normalized in normalizedKeys) continue

                val indexed = IndexedFact(session.query, session.timestamp, fact)
                allFacts.add(indexed)
                factsByContent[fact.content] = indexed
                normalizedKeys.add(normalized)
                totalFacts++
            }
        }

        if (allFacts.isEmpty()) return 0


        val factsToIndex = if (allFacts.size > MAX_INDEXED_FACTS) {
            logger.warn { "Capping memory index to $MAX_INDEXED_FACTS most recent facts (${allFacts.size} total)" }
            onProgress?.invoke("Indexing $MAX_INDEXED_FACTS of ${allFacts.size} facts (most recent)...")
            allFacts.take(MAX_INDEXED_FACTS)
        } else {
            onProgress?.invoke("Indexing $totalFacts facts from ${sessions.size} past sessions...")
            allFacts.toList()
        }

        logger.info { "Building memory RAG index: ${factsToIndex.size} facts from ${sessions.size} sessions" }

        val memoryRag = DocumentRAG(ollamaBaseUrl)
        val chunks = factsToIndex.mapIndexed { i, indexed ->
            TextChunk(
                content = "${indexed.fact.content}\nSource query: ${indexed.query}\nSources: ${indexed.fact.sources.joinToString(", ")}",
                source = "memory-fact",
                index = i
            )
        }
        memoryRag.indexChunks(chunks, onProgress)

        rag = memoryRag
        logger.info { "Memory RAG index ready: ${factsToIndex.size} facts" }
        return totalFacts
    }


    suspend fun retrieveRelevantFacts(query: String, topK: Int = 10): String {
        val memoryRag = rag ?: return ""
        if (!memoryRag.isReady) return ""


        val chunks = memoryRag.queryWithMinScore(query, topK, MIN_RETRIEVAL_SCORE)
        if (chunks.isEmpty()) return ""

        val matchedFacts = chunks.mapNotNull { chunk ->
            val contentLine = chunk.content.lines().firstOrNull() ?: return@mapNotNull null
            factsByContent[contentLine]
        }.distinctBy { it.fact.content }

            .sortedByDescending { it.timestamp }

        if (matchedFacts.isEmpty()) return ""

        return MemoryPrompts.formatMemoryContext(
            matchedFacts.map { Pair(it.query, it.fact) }
        )
    }

    var currentSessionId: String? = null
        private set


    fun prepareSession(): String {
        val now = LocalDateTime.now()
        val id = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss-SSS"))
        currentSessionId = id
        return id
    }

    suspend fun saveSession(
        query: String,
        model: String,
        facts: List<ResearchFact>,
        reportPath: String = "",
        conversationTurns: List<SerializableConversationTurn> = emptyList()
    ): String {
        return fileMutex.withLock {
            sessionsDir.mkdirs()

            val id = currentSessionId ?: run {
                val now = LocalDateTime.now()
                now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss-SSS"))
            }
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val slug = query.take(40).replace(Regex("[^a-zA-Z0-9]+"), "-").trim('-').lowercase()


            val qualityFacts = facts.filter { isQualityFact(it) }

            val session = SessionRecord(
                id = id, query = query, timestamp = timestamp,
                model = model, facts = qualityFacts, reportPath = reportPath,
                conversationTurns = conversationTurns
            )

            val file = File(sessionsDir, "${id}_$slug.json")
            writeAtomically(file, memoryJson.encodeToString(session))
            logger.info { "Saved session with ${qualityFacts.size} facts (${facts.size - qualityFacts.size} filtered): ${file.name}" }

            updateIndex(SessionIndexEntry(id, query, timestamp, qualityFacts.size))
            currentSessionId = id


            for (fact in qualityFacts) {
                val normalized = normalizeContent(fact.content)
                if (normalized !in normalizedKeys) {
                    val indexed = IndexedFact(query, timestamp, fact)
                    allFacts.add(indexed)
                    factsByContent[fact.content] = indexed
                    normalizedKeys.add(normalized)
                }
            }

            id
        }
    }

    suspend fun appendFacts(sessionId: String, newFacts: List<ResearchFact>) {
        val qualityFacts = newFacts.filter { isQualityFact(it) }
        if (qualityFacts.isEmpty()) return

        fileMutex.withLock {
            val sessionFiles = sessionsDir.listFiles { f -> f.name.startsWith(sessionId) } ?: return
            val file = sessionFiles.firstOrNull() ?: return

            try {
                val session = memoryJson.decodeFromString<SessionRecord>(file.readText())
                val updated = session.copy(facts = session.facts + qualityFacts)
                writeAtomically(file, memoryJson.encodeToString(updated))

                val newChunks = mutableListOf<TextChunk>()
                for (fact in qualityFacts) {
                    val normalized = normalizeContent(fact.content)
                    if (normalized in normalizedKeys) continue

                    val indexed = IndexedFact(session.query, session.timestamp, fact)
                    allFacts.add(indexed)
                    factsByContent[fact.content] = indexed
                    normalizedKeys.add(normalized)

                    newChunks.add(TextChunk(
                        content = "${fact.content}\nSource query: ${session.query}\nSources: ${fact.sources.joinToString(", ")}",
                        source = "memory-fact",
                        index = allFacts.size - 1
                    ))
                }

                val memoryRag = rag
                if (memoryRag != null && memoryRag.isReady && newChunks.isNotEmpty()) {
                    memoryRag.addChunks(newChunks)
                }

                logger.info { "Appended ${qualityFacts.size} facts to session $sessionId" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to append facts to session $sessionId" }
            }
        }
    }

    suspend fun saveConversationTurns(sessionId: String, turns: List<ConversationTurn>) {
        fileMutex.withLock {
            val sessionFiles = sessionsDir.listFiles { f -> f.name.startsWith(sessionId) } ?: return
            val file = sessionFiles.firstOrNull() ?: return

            try {
                val session = memoryJson.decodeFromString<SessionRecord>(file.readText())
                val updated = session.copy(
                    conversationTurns = turns.map { it.toSerializable() }
                )
                writeAtomically(file, memoryJson.encodeToString(updated))
                logger.info { "Saved ${turns.size} conversation turns to session $sessionId" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to save conversation turns to session $sessionId" }
            }
        }
    }

    fun forgetCurrentSession(sessionId: String) {
        val sessionFiles = sessionsDir.listFiles { f -> f.name.startsWith(sessionId) } ?: return
        val sessionQueries = sessionFiles.mapNotNull { file ->
            try { memoryJson.decodeFromString<SessionRecord>(file.readText()).query }
            catch (_: Exception) { null }
        }.toSet()
        sessionFiles.forEach { it.delete() }
        val removed = allFacts.filter { it.query in sessionQueries }
        allFacts.removeAll(removed.toSet())
        removed.forEach { indexed ->
            factsByContent.remove(indexed.fact.content)
            normalizedKeys.remove(normalizeContent(indexed.fact.content))
        }
        rebuildIndex()
        logger.info { "Forgot session $sessionId (${sessionQueries.size} queries removed)" }
    }


    fun getStats(): MemoryStats {
        val sessionFiles = sessionsDir.listFiles { f -> f.extension == "json" } ?: emptyArray()
        val sessions = sessionFiles.mapNotNull { file ->
            try { memoryJson.decodeFromString<SessionRecord>(file.readText()) }
            catch (_: Exception) { null }
        }.sortedByDescending { it.timestamp }

        return MemoryStats(
            sessionCount = sessions.size,
            totalFacts = allFacts.size,
            indexedFacts = rag?.indexedChunks ?: 0,
            recentSessions = sessions.take(5).map { SessionSummary(it.query, it.timestamp, it.facts.size) }
        )
    }

    data class MemoryStats(
        val sessionCount: Int,
        val totalFacts: Int,
        val indexedFacts: Int,
        val recentSessions: List<SessionSummary>
    )

    data class SessionSummary(
        val query: String,
        val timestamp: String,
        val factCount: Int
    )


    private fun updateIndex(entry: SessionIndexEntry) {
        val index = if (indexFile.exists()) {
            try { memoryJson.decodeFromString<SessionIndex>(indexFile.readText()) }
            catch (_: Exception) { SessionIndex() }
        } else SessionIndex()

        index.sessions.add(entry)
        writeAtomically(indexFile, memoryJson.encodeToString(index))
    }

    private fun rebuildIndex() {
        val sessionFiles = sessionsDir.listFiles { f -> f.extension == "json" } ?: emptyArray()
        val entries = sessionFiles.mapNotNull { file ->
            try {
                val session = memoryJson.decodeFromString<SessionRecord>(file.readText())
                SessionIndexEntry(session.id, session.query, session.timestamp, session.facts.size)
            } catch (_: Exception) { null }
        }
        writeAtomically(indexFile, memoryJson.encodeToString(SessionIndex(entries.toMutableList())))
    }

    companion object {

        private fun writeAtomically(target: File, content: String) {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }


        fun isQualityFact(fact: ResearchFact): Boolean {
            val content = fact.content.trim()
            if (content.length < MIN_FACT_LENGTH) return false
            if (META_PATTERNS.any { it.containsMatchIn(content) }) return false
            return true
        }


        fun normalizeContent(content: String): String {
            return content.lowercase()
                .replace(Regex("\\s+"), " ")
                .replace(Regex("[^a-z0-9 ]"), "")
                .trim()
        }
    }
}
