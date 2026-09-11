package org.sift.server.agents.watch

import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.sift.server.agents.AgentKind
import org.sift.server.agents.AgentPhase
import org.sift.server.agents.RunSource
import org.sift.server.agents.web.AgentRunResponse
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRunEventsTest {
    private val events = AgentRunEvents()

    @Test
    fun `emitting without subscribers neither blocks nor is replayed later`() = runBlocking {
        events.emit(event(AgentRunEventType.UPDATED, "before"))

        val received = withTimeout(TIMEOUT_MILLIS) {
            events.events
                .onSubscription { events.emit(event(AgentRunEventType.UPDATED, "after")) }
                .take(1)
                .toList()
        }

        assertEquals(listOf("after"), received.map { it.run.crName })
    }

    @Test
    fun `a slow subscriber sees the newest buffered events and the oldest are dropped`() = runBlocking {
        val total = AgentRunEvents.BUFFER_CAPACITY + OVERFLOW
        val received = withTimeout(TIMEOUT_MILLIS) {
            events.events
                .onSubscription { repeat(total) { events.emit(event(AgentRunEventType.UPDATED, "run-$it")) } }
                .take(AgentRunEvents.BUFFER_CAPACITY)
                .toList()
        }

        assertEquals(AgentRunEvents.BUFFER_CAPACITY, received.size)
        assertEquals("run-$OVERFLOW", received.first().run.crName)
        assertEquals("run-${total - 1}", received.last().run.crName)
        assertTrue(received.all { it.type == AgentRunEventType.UPDATED })
    }

    private fun event(type: AgentRunEventType, crName: String): AgentRunEvent = AgentRunEvent(type, response(crName))

    companion object {
        private const val TIMEOUT_MILLIS = 5_000L
        private const val OVERFLOW = 44
        private val NOW: OffsetDateTime = OffsetDateTime.parse("2026-09-07T10:00:00Z")

        fun response(
            crName: String,
            id: UUID = UUID.randomUUID(),
            kind: AgentKind = AgentKind.CODE_REVIEW,
            updatedAt: OffsetDateTime = NOW,
        ): AgentRunResponse = AgentRunResponse(
            id = id,
            kind = kind,
            source = RunSource.API,
            repositoryId = null,
            crName = crName,
            crUid = null,
            generation = null,
            executionId = null,
            phase = AgentPhase.CREATED,
            reason = null,
            message = null,
            spec = JsonMapper.builder().build().createObjectNode(),
            createdAt = NOW,
            startedAt = null,
            completedAt = null,
            updatedAt = updatedAt,
            createdBy = null,
            supersedesRunId = null,
            supersededByRunId = null,
        )
    }
}
