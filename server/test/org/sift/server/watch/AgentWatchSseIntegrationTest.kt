package org.sift.server.watch

import org.junit.jupiter.api.assertTimeoutPreemptively
import org.sift.server.agents.AgentPhase
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.io.BufferedReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** End-to-end over HTTP: the SSE wire format, snapshot-then-update ordering and the `/{id}` route separation. */
class AgentWatchSseIntegrationTest : WatchIntegrationTest() {
    @LocalServerPort
    private var port: Int = 0

    private val client: HttpClient = HttpClient.newHttpClient()
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `streams SNAPSHOT for existing runs and UPDATED after a committed change`() {
        val run = insertRun()
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/agents/watch?agentId=${run.id}"))
            .header("Accept", "text/event-stream")
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        assertEquals(HttpStatus.OK.value(), response.statusCode())
        assertTrue(assertNotNull(response.headers().firstValue("Content-Type").orElse(null)).startsWith("text/event-stream"))

        response.body().bufferedReader().use { reader ->
            assertTimeoutPreemptively(STREAM_TIMEOUT) {
                val snapshot = reader.nextFrame()
                assertEquals("SNAPSHOT", snapshot.event)
                assertEquals(run.updatedAt.toInstant().toEpochMilli().toString(), snapshot.id)
                assertEquals(run.id.toString(), snapshot.data("run")["id"].asString())
                assertEquals("CREATED", snapshot.data("run")["phase"].asString())

                val advanced = advance(run, AgentPhase.RUNNING)
                val updated = generateSequence { reader.nextFrame() }.first { it.event == "UPDATED" }
                assertEquals(advanced.updatedAt.toInstant().toEpochMilli().toString(), updated.id)
                assertEquals("RUNNING", updated.data("run")["phase"].asString())
                assertEquals("UPDATED", updated.data("type").asString())
            }
        }
    }

    @Test
    fun `watch is not swallowed by the run-by-id route and ids stay reachable`() {
        val run = insertRun()
        val rest = RestClient.create("http://localhost:$port")

        val byId = rest.get().uri("/api/v1/agents/{id}", run.id).retrieve().toEntity(String::class.java)
        assertEquals(HttpStatus.OK, byId.statusCode)

        val notAUuid = rest.get().uri("/api/v1/agents/watch").header("Accept", "application/json")
            .exchange { _, response -> response.statusCode }
        assertEquals(HttpStatus.NOT_ACCEPTABLE, notAUuid)
    }

    private data class Frame(val event: String?, val id: String?, val data: String?)

    private fun Frame.data(field: String) = assertNotNull(mapper.readTree(assertNotNull(data))[field])

    /** Reads until a blank line; comment-only frames (heartbeats) are skipped. */
    private fun BufferedReader.nextFrame(): Frame {
        while (true) {
            var event: String? = null
            var id: String? = null
            var data: String? = null
            var line = readLine() ?: error("stream ended")
            while (line.isNotEmpty()) {
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("id:") -> id = line.removePrefix("id:").trim()
                    line.startsWith("data:") -> data = (data ?: "") + line.removePrefix("data:").trim()
                }
                line = readLine() ?: error("stream ended")
            }
            if (event != null || id != null || data != null) {
                return Frame(event, id, data)
            }
        }
    }

    companion object {
        private val STREAM_TIMEOUT = Duration.ofSeconds(15)
    }
}
