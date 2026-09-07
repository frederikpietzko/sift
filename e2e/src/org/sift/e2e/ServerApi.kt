package org.sift.e2e

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Status code plus parsed body (`null` when the body is empty or not JSON). */
data class ApiResponse(val status: Int, val rawBody: String, val body: JsonNode?) {
    /** The body as JSON; fails with the raw body in the message when the server did not return JSON. */
    fun json(): JsonNode = body ?: error("Expected a JSON body but got HTTP $status: $rawBody")
}

/** One server-sent event; `data` is the concatenation of all `data:` lines of the frame. */
data class SseFrame(val event: String?, val id: String?, val data: String?)

/**
 * Minimal SSE frame reader over a text stream, ported from `ServerEndToEndTest.nextFrame`: reads
 * until a blank line and skips comment-only frames (the server's `:heartbeat` keep-alives).
 */
class SseFrameReader(private val reader: BufferedReader) {
    /** Blocks until a non-comment frame is complete; throws once the stream ends. */
    fun nextFrame(): SseFrame {
        while (true) {
            val frame = readRawFrame()
            if (frame.event != null || frame.id != null || frame.data != null) return frame
        }
    }

    /** Lazily yields frames until the stream ends. */
    fun frames(): Sequence<SseFrame> = generateSequence { nextFrame() }

    private fun readRawFrame(): SseFrame {
        var event: String? = null
        var id: String? = null
        var data: String? = null
        var line = reader.readLine() ?: error("SSE stream ended")
        while (line.isNotEmpty()) {
            when {
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("id:") -> id = line.removePrefix("id:").trim()
                line.startsWith("data:") -> data = data.orEmpty() + line.removePrefix("data:").trim()
            }
            line = reader.readLine() ?: error("SSE stream ended")
        }
        return SseFrame(event, id, data)
    }

    companion object {
        val defaultMapper: JsonMapper = JsonMapper.builder().build()

        /** Extracts the `run` object from a watch frame's JSON payload. */
        fun SseFrame.payload(mapper: JsonMapper = defaultMapper): JsonNode {
            val body = data ?: error("SSE frame '$event' carries no data")
            val run = mapper.readTree(body).path("run")
            return if (run.isMissingNode || run.isNull) error("SSE frame '$event' has no 'run' payload: $body") else run
        }
    }
}

/** An open `GET /api/v1/agents/watch` connection; closing it terminates the HTTP stream. */
class SseStream(private val response: HttpResponse<InputStream>) : Closeable {
    val status: Int get() = response.statusCode()
    private val bufferedReader = response.body().bufferedReader()
    val reader: SseFrameReader = SseFrameReader(bufferedReader)

    override fun close() {
        bufferedReader.close()
    }
}

/**
 * Thin JDK `HttpClient` + Jackson 3 client for the server's public API. Deliberately free of Spring
 * so the e2e module never shares a classpath with the server it is testing.
 */
class ServerApi(private val baseUrl: String) {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
    private val mapper = SseFrameReader.defaultMapper

    fun post(path: String, json: String): ApiResponse {
        val request = HttpRequest.newBuilder(uri(path))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString()).toApiResponse()
    }

    fun get(path: String): ApiResponse {
        val request = HttpRequest.newBuilder(uri(path))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .GET()
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString()).toApiResponse()
    }

    /** `GET path` asserting HTTP 200 and returning the parsed JSON body. */
    fun getJson(path: String): JsonNode {
        val response = get(path)
        check(response.status == HTTP_OK) { "GET $path returned HTTP ${response.status}: ${response.rawBody}" }
        return response.json()
    }

    /** Opens the SSE watch for one run; the caller owns the returned stream. */
    fun watch(agentId: String): SseStream {
        val request = HttpRequest.newBuilder(uri("/api/v1/agents/watch?agentId=$agentId"))
            .header("Accept", "text/event-stream")
            .GET()
            .build()
        return SseStream(http.send(request, HttpResponse.BodyHandlers.ofInputStream()))
    }

    private fun uri(path: String): URI = URI.create(baseUrl.trimEnd('/') + path)

    private fun HttpResponse<String>.toApiResponse(): ApiResponse {
        val raw = body().orEmpty()
        val parsed = raw.takeIf { it.isNotBlank() }?.let { runCatching { mapper.readTree(it) }.getOrNull() }
        return ApiResponse(statusCode(), raw, parsed)
    }

    companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_ACCEPTED = 202
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
