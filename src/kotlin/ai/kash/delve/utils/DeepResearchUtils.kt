package ai.kash.delve.utils

import ai.kash.delve.config.DeepResearchConfig
import ai.kash.delve.model.ResearchSummary
import ai.kash.delve.prompts.ResearchPrompts
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.executeStructured
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Per-stream search progress callbacks. Each [DeepResearchGraph.stream()] call creates
 * its own instance, eliminating the race condition of the previous global singleton.
 * Pass `null` for non-streaming (invoke) calls.
 */
data class SearchProgressCallbacks(
    val onSearchQuery: (suspend (String) -> Unit)? = null,
    val onSourceFound: (suspend (String, String) -> Unit)? = null
)

@Serializable
data class TavilySearchRequest(
    val query: String,
    val max_results: Int = 5,
    val include_raw_content: Boolean = true,
    val search_depth: String = "advanced",
    val topic: String = "general"
)

@Serializable
data class TavilySearchResponse(
    val query: String,
    val results: List<TavilySearchResult>
)

@Serializable
data class TavilySearchResult(
    val title: String,
    val url: String,
    val content: String,
    val raw_content: String? = null,
    val score: Double? = null
)

private val jsonCodec = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

object HttpClientProvider {
    private val clients = java.util.concurrent.ConcurrentHashMap<Pair<Long, Long>, OkHttpClient>()

    fun client(
        requestTimeoutMs: Long = 60_000,
        connectTimeoutMs: Long = 10_000
    ): OkHttpClient {
        return clients.computeIfAbsent(requestTimeoutMs to connectTimeoutMs) {
            OkHttpClient.Builder()
                .connectTimeout(connectTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(requestTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .writeTimeout(requestTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
        }
    }
}

suspend fun tavilySearchAsync(
    searchQueries: List<String>,
    config: DeepResearchConfig,
    maxResults: Int = 5,
    topic: String = "general",
    includeRawContent: Boolean = true,
    callbacks: SearchProgressCallbacks? = null
): List<TavilySearchResponse> {
    val apiKey = config.tavilyApiKey ?: return emptyList()
    val client = HttpClientProvider.client(
        requestTimeoutMs = config.httpRequestTimeoutMs,
        connectTimeoutMs = config.httpConnectTimeoutMs
    )

    val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    return coroutineScope {
        searchQueries.map { query ->
            async(Dispatchers.IO) {
                try {
                    callbacks?.onSearchQuery?.invoke(query)
                    val body = jsonCodec.encodeToString(TavilySearchRequest.serializer(), TavilySearchRequest(
                        query = query,
                        max_results = maxResults,
                        include_raw_content = includeRawContent,
                        search_depth = config.tavilySearchDepth,
                        topic = topic
                    ))
                    val request = Request.Builder()
                        .url("https://api.tavily.com/search")
                        .addHeader("Authorization", "Bearer $apiKey")
                        .post(body.toRequestBody(jsonMediaType))
                        .build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Tavily API error ${response.code}")
                    }
                    val result = jsonCodec.decodeFromString(TavilySearchResponse.serializer(), response.body.string())
                    for (source in result.results) {
                        callbacks?.onSourceFound?.invoke(source.url, source.title)
                    }
                    result
                } catch (e: Exception) {
                    val msg = e.message?.lowercase() ?: ""
                    // Propagate auth/quota errors — these are actionable
                    if (msg.contains("401") || msg.contains("403") || msg.contains("unauthorized")) {
                        throw e
                    }
                    retryLogger.warn { "Search failed for '$query': ${e.message}" }
                    TavilySearchResponse(query = query, results = emptyList())
                }
            }
        }.awaitAll()
    }
}

private const val DEFAULT_FALLBACK_CONTENT_LENGTH = 10000

suspend fun summarizeWebpage(
    executor: PromptExecutor,
    model: LLModel,
    webpageContent: String,
    timeoutMs: Long = 60_000,
    fallbackContentLength: Int = DEFAULT_FALLBACK_CONTENT_LENGTH
): String {
    return try {
        withTimeout(timeoutMs) {
            val summarizationPrompt = prompt("summarization") {
                user(ResearchPrompts.summarizeWebpagePrompt(webpageContent))
            }

            val result = executor.executeStructured(
                prompt = summarizationPrompt,
                model = model,
                serializer = serializer<ResearchSummary>()
            )

            val summary = result.getOrThrow().data

            buildString {
                appendLine("<summary>")
                appendLine(summary.summary)
                appendLine("</summary>")
                if (!summary.keyExcerpts.isNullOrBlank()) {
                    appendLine()
                    appendLine("<key_excerpts>")
                    appendLine(summary.keyExcerpts)
                    appendLine("</key_excerpts>")
                }
            }
        }
    } catch (_: TimeoutCancellationException) {
        webpageContent.take(fallbackContentLength) + "\n\n[Summary timed out - using truncated content]"
    } catch (e: Exception) {
        webpageContent.take(fallbackContentLength) + "\n\n[Summarization failed: ${e.message}]"
    }
}

suspend fun tavilySearch(
    queries: List<String>,
    config: DeepResearchConfig,
    summarizationExecutor: PromptExecutor? = null,
    summarizationModel: LLModel? = null,
    maxResults: Int = config.tavilySearchMaxResults,
    topic: String = "general",
    callbacks: SearchProgressCallbacks? = null
): String {
    val searchResults = tavilySearchAsync(
        searchQueries = queries,
        config = config,
        maxResults = maxResults,
        topic = topic,
        includeRawContent = config.tavilyIncludeRawContent,
        callbacks = callbacks
    )


    val uniqueResults = mutableMapOf<String, Pair<TavilySearchResult, String>>()
    for (response in searchResults) {
        for (result in response.results) {
            if (result.url !in uniqueResults) {
                uniqueResults[result.url] = Pair(result, response.query)
            }
        }
    }


    val model = summarizationModel ?: config.summarizationModel
    val processedResults = if (summarizationExecutor != null) {
        coroutineScope {
            uniqueResults.mapValues { (_, pair) ->
                val (result, query) = pair
                val rawContent = result.raw_content

                val summarizationThreshold = 2000
                if (!rawContent.isNullOrBlank() && rawContent.length > summarizationThreshold) {
                    async {
                        val summary = summarizeWebpage(
                            executor = summarizationExecutor,
                            model = model,
                            webpageContent = rawContent.take(config.maxContentLength),
                            timeoutMs = config.summarizationTimeoutMs,
                            fallbackContentLength = config.fallbackContentLength
                        )
                        Pair(result.copy(raw_content = summary), query)
                    }
                } else {
                    async { pair }
                }
            }.mapValues { it.value.await() }
        }
    } else {
        uniqueResults
    }

    if (processedResults.isEmpty()) {
        return "No valid search results found. Please try different search queries or use a different search API."
    }

    val sb = StringBuilder("Search results: \n\n")
    processedResults.entries.forEachIndexed { index, (url, pair) ->
        val (result, query) = pair
        sb.appendLine("\n--- SOURCE ${index + 1}: ${result.title} ---")
        sb.appendLine("URL: $url")
        sb.appendLine()

        val content = if (!result.raw_content.isNullOrBlank()) {
            val truncated = result.raw_content.take(config.maxContentLength)
            if (result.raw_content.length > config.maxContentLength) {
                "$truncated... [truncated]"
            } else {
                truncated
            }
        } else {
            result.content
        }

        sb.appendLine("CONTENT:")
        sb.appendLine(content)
        sb.appendLine()
        sb.appendLine("-".repeat(80))
    }

    return sb.toString()
}

private val retryLogger = io.github.oshai.kotlinlogging.KotlinLogging.logger("RetryHelper")

suspend fun <T> retryWithBackoff(
    maxRetries: Int = 5,
    initialDelayMs: Long = 2000,
    maxDelayMs: Long = 30000,
    block: suspend () -> T
): T {
    var attempt = 0
    var delay = initialDelayMs
    while (true) {
        try {
            return block()
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            val isRetryable = msg.contains("429") || msg.contains("too many") || msg.contains("rate limit")
                || msg.contains("500") || msg.contains("502") || msg.contains("503")
                || e is java.net.ConnectException || e is java.net.SocketTimeoutException
                || msg.contains("connection refused") || msg.contains("connection reset")
            if (!isRetryable || attempt >= maxRetries) throw e
            attempt++
            retryLogger.info { "Transient error (${e.javaClass.simpleName}, attempt $attempt/$maxRetries), retrying in ${delay}ms..." }
            delay(delay)
            delay = minOf(delay * 2, maxDelayMs)
        }
    }
}

fun thinkTool(reflection: String): String {
    return "Reflection recorded: $reflection"
}


fun isTokenLimitExceeded(exception: Exception): Boolean {
    val errorStr = exception.message?.lowercase() ?: return false
    if (checkGenericTokenLimit(errorStr)) return true
    return checkStructuredTokenLimit(exception)
}

private fun checkGenericTokenLimit(errorStr: String): Boolean {
    val tokenPatterns = listOf(
        "context length", "token limit", "maximum context", "context window",
        "too many tokens", "token overflow", "exceeds.*context",
        "too long.*context", "input.*too long",
        "max_tokens", "prompt is too long", "request too large",
        "model's maximum", "reduce.*prompt", "reduce.*input"
    )
    return tokenPatterns.any { Regex(it).containsMatchIn(errorStr) }
}

private fun checkStructuredTokenLimit(exception: Exception): Boolean {
    var cause: Throwable? = exception.cause
    while (cause != null) {
        val msg = cause.message?.lowercase() ?: ""
        if (checkGenericTokenLimit(msg)) return true
        cause = cause.cause
    }
    return false
}
