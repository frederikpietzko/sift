package org.sift.server.agents.watch

import org.junit.jupiter.api.assertTimeoutPreemptively
import org.sift.server.agents.AgentPhase
import org.sift.server.security.TestTokens
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.io.BufferedReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
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
            .header(HttpHeaders.AUTHORIZATION, TestTokens.authorization())
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
    fun `mine restricts snapshot and live events to runs created by the caller`() {
        val alice = provisionUser()
        val bob = provisionUser(subject = "0a1b2c3d-0000-4000-8000-000000000002", username = "bob")
        val mine = insertRun(createdBy = alice)
        val theirs = insertRun(createdBy = bob)
        val external = insertRun()
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/agents/watch?mine=true"))
            .header("Accept", "text/event-stream")
            .header(HttpHeaders.AUTHORIZATION, TestTokens.authorization())
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        assertEquals(HttpStatus.OK.value(), response.statusCode())

        response.body().bufferedReader().use { reader ->
            assertTimeoutPreemptively(STREAM_TIMEOUT) {
                // the snapshot ends with (or at least contains) alice's run; every frame until then is hers
                val snapshot = generateSequence { reader.nextFrame() }
                    .takeWhileInclusive { it.data("run")["id"].asString() != mine.id.toString() }
                    .toList()
                assertTrue(snapshot.all { it.event == "SNAPSHOT" })
                assertTrue(snapshot.all { it.data("run")["createdBy"]["id"].asString() == alice.id.toString() })
                assertTrue(snapshot.all { it.data("run")["createdBy"]["username"].asString() == TestTokens.USERNAME })

                advance(theirs, AgentPhase.RUNNING)
                advance(external, AgentPhase.RUNNING)
                val advanced = advance(mine, AgentPhase.RUNNING)
                val updated = generateSequence { reader.nextFrame() }.first { it.event == "UPDATED" }
                assertEquals(advanced.id.toString(), updated.data("run")["id"].asString())
                assertEquals(advanced.updatedAt.toInstant().toEpochMilli().toString(), updated.id)
                assertEquals(alice.id.toString(), updated.data("run")["createdBy"]["id"].asString())
            }
        }
    }

    @Test
    fun `mine combined with a foreign createdBy is a 400 problem detail`() {
        val rest = RestClient.builder()
            .baseUrl("http://localhost:$port")
            .defaultHeader(HttpHeaders.AUTHORIZATION, TestTokens.authorization())
            .build()
        val status = rest.get().uri("/api/v1/agents/watch?mine=true&createdBy={id}", UUID.randomUUID())
            .header("Accept", "text/event-stream")
            .exchange { _, response -> response.statusCode }
        assertEquals(HttpStatus.BAD_REQUEST, status)
    }

    @Test
    fun `watch is not swallowed by the run-by-id route and ids stay reachable`() {
        val run = insertRun()
        val rest = RestClient.builder()
            .baseUrl("http://localhost:$port")
            .defaultHeader(HttpHeaders.AUTHORIZATION, TestTokens.authorization())
            .build()

        val byId = rest.get().uri("/api/v1/agents/{id}", run.id).retrieve().toEntity(String::class.java)
        assertEquals(HttpStatus.OK, byId.statusCode)

        val notAUuid = rest.get().uri("/api/v1/agents/watch").header("Accept", "application/json")
            .exchange { _, response -> response.statusCode }
        assertEquals(HttpStatus.NOT_ACCEPTABLE, notAUuid)
    }

    @Test
    fun `watch stream requires a bearer token`() {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/agents/watch"))
            .header("Accept", "text/event-stream")
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.statusCode())
        assertTrue(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE).orElse("").startsWith("Bearer"))
    }

    private data class Frame(val event: String?, val id: String?, val data: String?)

    private fun Frame.data(field: String) = assertNotNull(mapper.readTree(assertNotNull(data))[field])

    private fun <T> Sequence<T>.takeWhileInclusive(predicate: (T) -> Boolean): Sequence<T> = sequence {
        for (element in this@takeWhileInclusive) {
            yield(element)
            if (!predicate(element)) break
        }
    }

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
