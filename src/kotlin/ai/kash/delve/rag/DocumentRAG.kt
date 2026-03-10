package ai.kash.delve.rag

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.local.LLMEmbedder
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.rag.base.mostRelevantDocuments
import ai.koog.rag.vector.EmbeddingBasedDocumentStorage
import ai.koog.rag.vector.InMemoryVectorStorage
import ai.koog.rag.vector.DocumentEmbedder
import ai.koog.embeddings.base.Vector
import ai.koog.prompt.executor.ollama.client.OllamaModels
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

data class TextChunk(
    val content: String,
    val source: String,
    val index: Int
)

class ChunkEmbedder(private val embedder: Embedder) : DocumentEmbedder<TextChunk> {
    override suspend fun embed(document: TextChunk): Vector = embedder.embed(document.content)
    override suspend fun embed(text: String): Vector = embedder.embed(text)
    override fun diff(embedding1: Vector, embedding2: Vector): Double = embedding1.cosineSimilarity(embedding2)
}

class DocumentRAG(
    private val ollamaBaseUrl: String = "http://localhost:11434"
) {
    private var storage: EmbeddingBasedDocumentStorage<TextChunk>? = null
    private var docEmbedder: ChunkEmbedder? = null
    private var chunkCount = 0

    suspend fun indexFiles(files: List<File>, onProgress: ((String) -> Unit)? = null): Int {
        ensureEmbeddingModel(onProgress)

        val client = OllamaClient(ollamaBaseUrl)
        val embedder = LLMEmbedder(client, OllamaModels.Embeddings.NOMIC_EMBED_TEXT)
        val chunkEmb = ChunkEmbedder(embedder)
        val vectorStorage = InMemoryVectorStorage<TextChunk>()
        val docStorage = EmbeddingBasedDocumentStorage(chunkEmb, vectorStorage)

        var totalChunks = 0

        for (file in files) {
            if (!file.exists() || !file.isFile) continue
            if (file.length() > 2_000_000) {
                logger.warn { "Skipping ${file.name}: too large (${file.length() / 1024}KB)" }
                continue
            }

            try {
                val text = file.readText()
                val chunks = chunkText(text, file.name)

                onProgress?.invoke("Indexing ${file.name} (${chunks.size} chunks)")
                logger.info { "Indexing ${file.name}: ${chunks.size} chunks" }

                for (chunk in chunks) {
                    docStorage.store(chunk)
                    totalChunks++
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to index ${file.name}" }
            }
        }

        storage = docStorage
        docEmbedder = chunkEmb
        chunkCount = totalChunks
        logger.info { "RAG index ready: $totalChunks chunks from ${files.size} files" }
        return totalChunks
    }

    /**
     * Index pre-built TextChunks directly, avoiding the file I/O round-trip of [indexFiles].
     * Used by [ai.kash.delve.memory.ResearchMemory] to index facts from memory without writing temp files.
     */
    suspend fun indexChunks(chunks: List<TextChunk>, onProgress: ((String) -> Unit)? = null): Int {
        if (chunks.isEmpty()) return 0

        ensureEmbeddingModel(onProgress)

        val client = OllamaClient(ollamaBaseUrl)
        val embedder = LLMEmbedder(client, OllamaModels.Embeddings.NOMIC_EMBED_TEXT)
        val chunkEmb = ChunkEmbedder(embedder)
        val vectorStorage = InMemoryVectorStorage<TextChunk>()
        val docStorage = EmbeddingBasedDocumentStorage(chunkEmb, vectorStorage)

        var totalChunks = 0
        for (chunk in chunks) {
            try {
                docStorage.store(chunk)
                totalChunks++
            } catch (e: Exception) {
                logger.warn(e) { "Failed to index chunk from ${chunk.source}" }
            }
        }

        storage = docStorage
        docEmbedder = chunkEmb
        chunkCount = totalChunks
        logger.info { "RAG index ready: $totalChunks chunks (in-memory)" }
        return totalChunks
    }

    /**
     * Append additional chunks to an existing index without rebuilding from scratch.
     * Requires that [indexChunks] or [indexFiles] was already called.
     */
    suspend fun addChunks(chunks: List<TextChunk>): Int {
        val store = storage ?: return 0
        var added = 0
        for (chunk in chunks) {
            try {
                store.store(chunk)
                added++
            } catch (e: Exception) {
                logger.warn(e) { "Failed to add chunk from ${chunk.source}" }
            }
        }
        chunkCount += added
        return added
    }

    suspend fun query(query: String, topK: Int = 5): List<TextChunk> {
        val store = storage ?: return emptyList()
        return try {
            store.mostRelevantDocuments(query, count = topK).toList()
        } catch (e: Exception) {
            logger.warn(e) { "RAG query failed" }
            emptyList()
        }
    }

    /**
     * Query with a minimum cosine similarity threshold.
     * Embeds the query once and compares against stored document vectors,
     * filtering out results below [minScore]. This prevents injecting irrelevant memory into prompts.
     */
    suspend fun queryWithMinScore(query: String, topK: Int = 5, minScore: Double = 0.3): List<TextChunk> {
        val store = storage ?: return emptyList()
        val embedder = docEmbedder ?: return query(query, topK)

        return try {

            val candidates = store.mostRelevantDocuments(query, count = topK * 2).toList()
            if (candidates.isEmpty()) return emptyList()


            val queryVec = embedder.embed(query)
            candidates
                .map { chunk -> chunk to embedder.diff(queryVec, embedder.embed(chunk)) }
                .filter { (_, score) -> score >= minScore }
                .sortedByDescending { (_, score) -> score }
                .take(topK)
                .map { (chunk, _) -> chunk }
        } catch (e: Exception) {
            logger.warn(e) { "RAG scored query failed, falling back to unscored" }
            query(query, topK)
        }
    }

    suspend fun buildContext(query: String, topK: Int = 5, minScore: Double = 0.2): String {
        val chunks = if (docEmbedder != null) queryWithMinScore(query, topK, minScore) else query(query, topK)
        if (chunks.isEmpty()) return ""
        return buildString {
            appendLine("=== Relevant context from attached documents ===")
            for (chunk in chunks) {
                appendLine()
                appendLine("--- [${chunk.source}, chunk ${chunk.index}] ---")
                appendLine(chunk.content)
            }
            appendLine("=== End of document context ===")
        }
    }

    val isReady: Boolean get() = storage != null
    val indexedChunks: Int get() = chunkCount

    private suspend fun ensureEmbeddingModel(onProgress: ((String) -> Unit)? = null) {
        withContext(Dispatchers.IO) {
            try {
                val check = ProcessBuilder("ollama", "list")
                    .redirectErrorStream(true).start()
                val exited = check.waitFor(30, TimeUnit.SECONDS)
                if (!exited) {
                    check.destroyForcibly()
                    logger.warn { "ollama list timed out" }
                    return@withContext
                }
                val output = check.inputStream.bufferedReader().readText()
                if (!output.contains("nomic-embed-text")) {
                    onProgress?.invoke("Pulling nomic-embed-text embedding model...")
                    logger.info { "Pulling nomic-embed-text model" }
                    val pull = ProcessBuilder("ollama", "pull", "nomic-embed-text")
                        .inheritIO().start()
                    if (!pull.waitFor(5, TimeUnit.MINUTES)) {
                        pull.destroyForcibly()
                        logger.warn { "ollama pull timed out" }
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Could not check/pull embedding model" }
            }
        }
    }
}


fun chunkText(
    text: String,
    source: String,
    chunkSize: Int = 1000,
    overlap: Int = 200
): List<TextChunk> {
    if (text.length <= chunkSize) {
        return listOf(TextChunk(content = text, source = source, index = 0))
    }

    val paragraphs = text.split(Regex("\n\\s*\n")).filter { it.isNotBlank() }
    val chunks = mutableListOf<TextChunk>()
    val buffer = StringBuilder()
    var chunkIndex = 0

    for (para in paragraphs) {
        if (buffer.length + para.length > chunkSize && buffer.isNotEmpty()) {
            chunks.add(
                TextChunk(
                    content = buffer.toString().trim(),
                    source = source,
                    index = chunkIndex++
                )
            )
            val overlapText = buffer.takeLast(overlap)
            buffer.clear()
            buffer.append(overlapText)
        }
        if (buffer.isNotEmpty()) buffer.append("\n\n")
        buffer.append(para)
    }

    if (buffer.isNotBlank()) {
        chunks.add(
            TextChunk(
                content = buffer.toString().trim(),
                source = source,
                index = chunkIndex
            )
        )
    }


    return chunks.flatMap { chunk ->
        if (chunk.content.length <= chunkSize * 2) {
            listOf(chunk)
        } else {
            splitOnSentenceBoundaries(chunk.content, chunkSize).mapIndexed { i, part ->
                TextChunk(content = part, source = chunk.source, index = chunk.index * 100 + i)
            }
        }
    }
}

private fun splitOnSentenceBoundaries(text: String, maxSize: Int): List<String> {
    val parts = mutableListOf<String>()
    var remaining = text
    while (remaining.length > maxSize) {
        val window = remaining.take(maxSize)

        val splitAt = maxOf(
            window.lastIndexOf(". "),
            window.lastIndexOf(".\n"),
            window.lastIndexOf("? "),
            window.lastIndexOf("! "),
            window.lastIndexOf('\n')
        )
        val cutPoint = if (splitAt > maxSize / 4) splitAt + 1 else maxSize
        parts.add(remaining.take(cutPoint).trim())
        remaining = remaining.drop(cutPoint).trimStart()
    }
    if (remaining.isNotBlank()) parts.add(remaining.trim())
    return parts
}
