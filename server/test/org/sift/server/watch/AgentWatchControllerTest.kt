package org.sift.server.watch

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.sift.server.agents.AgentKind
import org.sift.server.agents.AgentPhase
import org.sift.server.agents.AgentRun
import org.sift.server.agents.AgentRunFilter
import org.sift.server.agents.AgentRunRepository
import org.sift.server.agents.AgentRunResponse
import org.sift.server.agents.Page
import org.sift.server.agents.RunSource
import org.sift.server.config.ServerProperties
import org.sift.server.watch.AgentWatchController.Companion.toResumePoint
import org.springframework.http.codec.ServerSentEvent
import org.springframework.transaction.support.TransactionOperations
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Drives the controller's `Flow` directly: the repository is mocked, transactions run without a transaction
 * manager and live events are pushed through a real [AgentRunEvents].
 */
class AgentWatchControllerTest {
    private val runs = mockk<AgentRunRepository>()
    private val events = AgentRunEvents()
    private val service = AgentRunWatchService(runs, TransactionOperations.withoutTransaction(), events, Dispatchers.IO)

    private val first = run("cr-1", updatedAt = OffsetDateTime.parse("2026-09-07T10:00:00Z"))
    private val second = run("cr-2", updatedAt = OffsetDateTime.parse("2026-09-07T10:05:00Z"))

    @Test
    fun `streams a snapshot followed by live updates as named SSE frames with updatedAt ids`() = runBlocking {
        every { runs.list(AgentRunFilter(), 0, AgentRunWatchService.SNAPSHOT_LIMIT) } returns
            Page(items = listOf(second, first), page = 0, size = AgentRunWatchService.SNAPSHOT_LIMIT, total = 2)
        val updated = first.copy(phase = AgentPhase.RUNNING, updatedAt = OffsetDateTime.parse("2026-09-07T10:06:00Z"))

        val frames = collectData(controller().watch(agentId = null, kind = null, lastEventId = null), count = 3) {
            events.emit(AgentRunEvent(AgentRunEventType.UPDATED, AgentRunResponse.from(updated)))
        }

        assertEquals(listOf("SNAPSHOT", "SNAPSHOT", "UPDATED"), frames.map { it.event() })
        assertEquals(listOf("cr-2", "cr-1", "cr-1"), frames.map { assertNotNull(it.data()).run.crName })
        assertEquals(AgentPhase.RUNNING, assertNotNull(frames.last().data()).run.phase)
        assertEquals(updated.updatedAt.toInstant().toEpochMilli().toString(), frames.last().id())
    }

    @Test
    fun `filters live events by agentId and snapshots only that run`() = runBlocking {
        every { runs.findById(first.id) } returns first
        val other = second.copy(phase = AgentPhase.FAILED)
        val mine = first.copy(phase = AgentPhase.SUCCESS)

        val frames = collectData(controller().watch(agentId = first.id, kind = null, lastEventId = null), count = 2) {
            events.emit(AgentRunEvent(AgentRunEventType.UPDATED, AgentRunResponse.from(other)))
            events.emit(AgentRunEvent(AgentRunEventType.UPDATED, AgentRunResponse.from(mine)))
        }

        assertEquals(listOf("SNAPSHOT", "UPDATED"), frames.map { it.event() })
        assertEquals(listOf(first.id, first.id), frames.map { assertNotNull(it.data()).run.id })
        assertEquals(AgentPhase.SUCCESS, assertNotNull(frames.last().data()).run.phase)
        verify(exactly = 0) { runs.list(any(), any(), any()) }
    }

    @Test
    fun `resumes from Last-Event-ID with the runs updated since and filters by kind`() = runBlocking {
        val since = second.updatedAt.toInstant()
        every {
            runs.findUpdatedSince(
                since.atOffset(ZoneOffset.UTC),
                AgentRunFilter(kind = AgentKind.CODE_REVIEW),
                AgentRunWatchService.SNAPSHOT_LIMIT,
            )
        } returns listOf(second)

        val controller = controller()
        val frames = collectData(
            controller.watch(agentId = null, kind = AgentKind.CODE_REVIEW, lastEventId = since.toEpochMilli().toString()),
            count = 2,
        ) {
            events.emit(AgentRunEvent(AgentRunEventType.UPDATED, AgentRunResponse.from(first)))
        }

        assertEquals(listOf("SNAPSHOT", "UPDATED"), frames.map { it.event() })
        assertEquals(listOf("cr-2", "cr-1"), frames.map { assertNotNull(it.data()).run.crName })
        verify(exactly = 0) { runs.list(any(), any(), any()) }
    }

    @Test
    fun `emits heartbeat comments while idle`() = runBlocking {
        every { runs.list(any(), any(), any()) } returns Page(items = emptyList(), page = 0, size = 1, total = 0)

        val heartbeat = withTimeout(TIMEOUT_MILLIS) {
            controller(heartbeat = Duration.ofMillis(HEARTBEAT_MILLIS))
                .watch(agentId = null, kind = null, lastEventId = null)
                .first { it.comment() != null }
        }

        assertEquals(AgentWatchController.HEARTBEAT, heartbeat.comment())
        assertNull(heartbeat.data())
    }

    @Test
    fun `Last-Event-ID is parsed leniently`() {
        assertNull(null.toResumePoint())
        assertNull("not-a-number".toResumePoint())
        assertEquals(Instant.ofEpochMilli(1_757_239_200_000), " 1757239200000 ".toResumePoint())
    }

    private fun controller(heartbeat: Duration = Duration.ofHours(1)): AgentWatchController = AgentWatchController(
        service,
        ServerProperties(encryptionKey = "test", watch = ServerProperties.Watch(heartbeat = heartbeat)),
    )

    /** Collects [count] data frames; [afterSnapshot] runs once the first snapshot frame arrived. */
    private suspend fun collectData(
        stream: Flow<ServerSentEvent<AgentRunEvent>>,
        count: Int,
        afterSnapshot: () -> Unit,
    ): List<ServerSentEvent<AgentRunEvent>> = withTimeout(TIMEOUT_MILLIS) {
        var triggered = false
        stream
            .filter { it.data() != null }
            .onEach {
                if (!triggered) {
                    triggered = true
                    afterSnapshot()
                }
            }
            .take(count)
            .toList()
    }

    private fun run(crName: String, updatedAt: OffsetDateTime): AgentRun {
        val id = UUID.randomUUID()
        return AgentRun(
            id = id,
            kind = AgentKind.CODE_REVIEW,
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
            createdAt = updatedAt,
            startedAt = null,
            completedAt = null,
            observedAt = null,
            updatedAt = updatedAt,
        )
    }

    companion object {
        private const val TIMEOUT_MILLIS = 5_000L
        private const val HEARTBEAT_MILLIS = 50L
    }
}
