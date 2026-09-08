package org.sift.server.agents

import io.fabric8.kubernetes.client.KubernetesClientException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.sift.events.CodeReviewStatusChangedEvent
import org.sift.server.agents.adapters.AgentKindAdapter
import org.sift.server.agents.adapters.AppliedResource
import org.sift.server.agents.persistence.AgentRunRepository
import org.sift.server.agents.web.CreateAgentRunRequest
import org.sift.server.api.ConflictException
import org.sift.server.api.NotFoundException
import org.sift.server.api.Page
import org.sift.server.repositories.Repository
import org.sift.server.repositories.RepositoryService
import org.sift.server.users.TestUsers
import org.springframework.transaction.support.TransactionOperations
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class AgentRunServiceTest {
    private val runs = mockk<AgentRunRepository>(relaxed = true)
    private val adapter = mockk<AgentKindAdapter> {
        every { kind } returns AgentKind.CODE_REVIEW
        every { resourceName(any()) } answers { "cr-${firstArg<UUID>()}" }
    }
    private val repositories = mockk<RepositoryService>()
    private val cleanup = mockk<AgentRunCleanup>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"), ZoneOffset.UTC)
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val service = AgentRunService(
        runs = runs,
        adapters = listOf(adapter),
        cleanups = listOf(cleanup),
        repositories = repositories,
        transactions = TransactionOperations.withoutTransaction(),
        mapper = mapper,
        clock = clock,
    )

    private val repositoryId: UUID = UUID.fromString("9b2c0c8e-1f4e-4c21-a9c8-4b1b5e1f8d10")
    private val repository = Repository(
        id = repositoryId,
        name = "sift",
        url = "https://github.com/sift/sift.git",
        token = null,
        secretName = null,
        createdAt = OffsetDateTime.now(clock),
        updatedAt = OffsetDateTime.now(clock),
    )
    private val request = CreateAgentRunRequest(
        kind = AgentKind.CODE_REVIEW,
        repositoryId = repositoryId,
        branch = "feature/x",
        baseBranch = "main",
        commitSha = "a".repeat(SHA_LENGTH),
        pullRequest = "42",
    )

    init {
        every { repositories.get(repositoryId) } returns repository
        every { runs.insert(any()) } answers { firstArg() }
        every { runs.update(any()) } answers { firstArg() }
        every { runs.updateStatus(any()) } answers { firstArg() }
    }

    @Test
    fun `create persists a CREATED run applies the CR and records its uid`() {
        val inserted = slot<AgentRun>()
        every { runs.insert(capture(inserted)) } answers { inserted.captured }
        every { adapter.apply(any(), request) } answers { AppliedResource("cr-${firstArg<AgentRun>().id}", "uid-1") }

        val run = service.create(request, TestUsers.alice)

        assertEquals(AgentPhase.CREATED, inserted.captured.phase)
        assertEquals(RunSource.API, inserted.captured.source)
        assertEquals(repositoryId, inserted.captured.repositoryId)
        assertEquals(RunCreator(TestUsers.ALICE_ID, TestUsers.alice.username), inserted.captured.createdBy)
        assertEquals("cr-${run.id}", inserted.captured.crName)
        assertNull(inserted.captured.crUid)
        assertEquals("https://github.com/sift/sift.git", inserted.captured.spec["repositoryUrl"].asString())
        assertEquals("feature/x", inserted.captured.spec["branch"].asString())
        assertEquals("main", inserted.captured.spec["baseBranch"].asString())
        assertEquals("a".repeat(SHA_LENGTH), inserted.captured.spec["commitSha"].asString())
        assertEquals("42", inserted.captured.spec["pullRequest"].asString())
        assertEquals(OffsetDateTime.now(clock), inserted.captured.createdAt)

        assertEquals("uid-1", run.crUid)
        assertEquals("cr-${run.id}", run.crName)
        assertEquals(AgentPhase.CREATED, run.phase)
        assertEquals(RunCreator(TestUsers.ALICE_ID, TestUsers.alice.username), run.createdBy)
        verify(exactly = 1) { adapter.apply(inserted.captured, request) }
        verify(exactly = 1) { runs.update(run) }
    }

    @Test
    fun `create marks the run FAILED and rethrows when applying the CR fails`() {
        val updated = slot<AgentRun>()
        every { runs.update(capture(updated)) } answers { updated.captured }
        every { adapter.apply(any(), request) } throws KubernetesClientException("forbidden")

        val thrown = assertFailsWith<KubernetesClientException> { service.create(request, TestUsers.alice) }

        assertEquals("forbidden", thrown.message)
        assertEquals(AgentPhase.FAILED, updated.captured.phase)
        assertEquals(AgentRunService.REASON_APPLY_FAILED, updated.captured.reason)
        assertEquals("forbidden", updated.captured.message)
        assertEquals(OffsetDateTime.now(clock), updated.captured.completedAt)
        verify(exactly = 1) { runs.insert(any()) }
    }

    @Test
    fun `create rethrows the apply failure with the bookkeeping failure suppressed`() {
        every { runs.update(any()) } throws IllegalStateException("db down")
        every { adapter.apply(any(), request) } throws KubernetesClientException("forbidden")

        val thrown = assertFailsWith<KubernetesClientException> { service.create(request, TestUsers.alice) }

        assertEquals("forbidden", thrown.message)
        assertEquals(listOf("db down"), thrown.suppressed.map { it.message })
        verify(exactly = 1) { runs.update(any()) }
    }

    @Test
    fun `create rejects an unknown repository before persisting anything`() {
        val unknown = UUID.randomUUID()
        every { repositories.get(unknown) } throws NotFoundException("Repository $unknown not found")
        assertFailsWith<NotFoundException> { service.create(request.copy(repositoryId = unknown), TestUsers.alice) }
        verify(exactly = 0) { runs.insert(any()) }
        verify(exactly = 0) { adapter.apply(any(), any()) }
    }

    @Test
    fun `cancel deletes the CR and marks the run CANCELLED`() {
        val run = run(phase = AgentPhase.RUNNING)
        every { runs.findById(run.id) } returns run
        every { adapter.delete(run) } returns Unit

        val cancelled = service.cancel(run.id)

        assertEquals(AgentPhase.CANCELLED, cancelled.phase)
        assertEquals(AgentRunService.REASON_CANCELLED, cancelled.reason)
        assertNull(cancelled.message)
        assertEquals(OffsetDateTime.now(clock), cancelled.completedAt)
        assertEquals(OffsetDateTime.now(clock), cancelled.updatedAt)
        verify(exactly = 1) { adapter.delete(run) }
        verify(exactly = 1) { runs.update(cancelled) }
    }

    @Test
    fun `cancel of a terminal run is a conflict and unknown ids are not found`() {
        listOf(AgentPhase.SUCCESS, AgentPhase.FAILED, AgentPhase.CANCELLED).forEach { phase ->
            val run = run(phase = phase)
            every { runs.findById(run.id) } returns run
            assertFailsWith<ConflictException>(phase.name) { service.cancel(run.id) }
        }
        val missing = UUID.randomUUID()
        every { runs.findById(missing) } returns null
        assertFailsWith<NotFoundException> { service.cancel(missing) }
        verify(exactly = 0) { adapter.delete(any()) }
        verify(exactly = 0) { runs.update(any()) }
    }

    @Test
    fun `delete cancels a running run, lets other modules clean up and removes the row`() {
        val run = run(phase = AgentPhase.RUNNING)
        every { runs.findById(run.id) } returns run
        every { adapter.delete(run) } returns Unit
        every { runs.delete(run.id) } returns true

        service.delete(run.id)

        verify(exactly = 1) { adapter.delete(run) }
        verify(exactly = 1) { cleanup.deleteForRun(run.id) }
        verify(exactly = 1) { runs.delete(run.id) }
        verify(exactly = 0) { runs.update(any()) }
    }

    @Test
    fun `delete of a terminal run leaves the CR alone and unknown ids are not found`() {
        val run = run(phase = AgentPhase.SUCCESS)
        every { runs.findById(run.id) } returns run
        every { runs.delete(run.id) } returns true

        service.delete(run.id)

        verify(exactly = 0) { adapter.delete(any()) }
        verify(exactly = 1) { cleanup.deleteForRun(run.id) }
        verify(exactly = 1) { runs.delete(run.id) }

        val missing = UUID.randomUUID()
        every { runs.findById(missing) } returns null
        assertFailsWith<NotFoundException> { service.delete(missing) }
        verify(exactly = 0) { cleanup.deleteForRun(missing) }
        verify(exactly = 0) { runs.delete(missing) }
    }

    @Test
    fun `get and list delegate to the repository with a clamped page size`() {
        val run = run(phase = AgentPhase.PENDING)
        every { runs.findById(run.id) } returns run
        assertSame(run, service.get(run.id))

        val page = Page(items = listOf(run), page = 0, size = 200, total = 1L)
        every { runs.list(AgentRunFilter(), 0, 200) } returns page
        assertSame(page, service.list(AgentRunFilter(), page = -3, size = 5000))
        every { runs.list(AgentRunFilter(phase = AgentPhase.PENDING), 2, 1) } returns page
        assertSame(page, service.list(AgentRunFilter(phase = AgentPhase.PENDING), page = 2, size = 0))
    }

    @Test
    fun `completeWithResult promotes a non-terminal run to SUCCESS keeping generation and execution id`() {
        val run = run(phase = AgentPhase.RUNNING, generation = 3).copy(executionId = "uid:3")
        every { runs.findById(run.id) } returns run
        val completedAt = OffsetDateTime.now(clock).minusMinutes(1)

        val completed = assertNotNull(service.completeWithResult(run.id, completedAt))

        assertEquals(run.id, completed.id)
        assertEquals(AgentPhase.SUCCESS, completed.phase)
        assertEquals(AgentRunService.REASON_RESULT_RECEIVED, completed.reason)
        assertNull(completed.message)
        assertEquals(completedAt, completed.completedAt)
        assertEquals(OffsetDateTime.now(clock), completed.observedAt)
        assertEquals(OffsetDateTime.now(clock), completed.updatedAt)
        assertEquals("uid:3", completed.executionId)
        assertEquals(3L, completed.generation)
        verify(exactly = 1) { runs.updateStatus(completed) }
    }

    @Test
    fun `completeWithResult leaves terminal runs untouched and unknown ids are not found`() {
        listOf(AgentPhase.SUCCESS, AgentPhase.FAILED, AgentPhase.CANCELLED).forEach { phase ->
            val run = run(phase = phase)
            every { runs.findById(run.id) } returns run
            assertNull(service.completeWithResult(run.id, OffsetDateTime.now(clock)), phase.name)
        }
        val missing = UUID.randomUUID()
        every { runs.findById(missing) } returns null
        assertFailsWith<NotFoundException> { service.completeWithResult(missing, OffsetDateTime.now(clock)) }
        verify(exactly = 0) { runs.updateStatus(any()) }
    }

    @Test
    fun `findByExecutionId delegates to the repository`() {
        val run = run(phase = AgentPhase.RUNNING)
        every { runs.findByExecutionId("uid:1") } returns run
        assertSame(run, service.findByExecutionId("uid:1"))
        every { runs.findByExecutionId("nope") } returns null
        assertNull(service.findByExecutionId("nope"))
    }

    @Test
    fun `applyStatus inserts an EXTERNAL run for an unknown CR uid`() {
        every { runs.findByCrUid("uid-ext") } returns null
        val inserted = slot<AgentRun>()
        every { runs.insert(capture(inserted)) } answers { inserted.captured }

        service.applyStatus(event(uid = "uid-ext", phase = "RUNNING", generation = 2))

        val run = inserted.captured
        assertEquals(RunSource.EXTERNAL, run.source)
        assertEquals(AgentKind.CODE_REVIEW, run.kind)
        assertNull(run.repositoryId)
        assertNull(run.createdBy)
        assertEquals("review-x", run.crName)
        assertEquals("uid-ext", run.crUid)
        assertEquals(2L, run.generation)
        assertEquals("uid-ext:2", run.executionId)
        assertEquals(AgentPhase.RUNNING, run.phase)
        assertEquals("https://example.org/ext.git", run.spec["repositoryUrl"].asString())
        assertEquals("main", run.spec["baseBranch"].asString())
        assertEquals(OBSERVED.atOffset(ZoneOffset.UTC), run.observedAt)
        assertEquals(OffsetDateTime.now(clock), run.createdAt)
        verify(exactly = 0) { runs.updateStatus(any()) }
    }

    @Test
    fun `applyStatus updates a known run with a newer event`() {
        val stored = run(phase = AgentPhase.PENDING, generation = 1, observedAt = OBSERVED.minusSeconds(10))
        every { runs.findByCrUid(stored.crUid!!) } returns stored
        val updated = slot<AgentRun>()
        every { runs.updateStatus(capture(updated)) } answers { updated.captured }

        service.applyStatus(
            event(uid = stored.crUid!!, phase = "RUNNING", generation = 1, reason = "JobStarted", message = "go"),
        )

        assertEquals(stored.id, updated.captured.id)
        assertEquals(AgentPhase.RUNNING, updated.captured.phase)
        assertEquals("JobStarted", updated.captured.reason)
        assertEquals("go", updated.captured.message)
        assertEquals(1L, updated.captured.generation)
        assertEquals("${stored.crUid}:1", updated.captured.executionId)
        assertEquals(STARTED.atOffset(ZoneOffset.UTC), updated.captured.startedAt)
        assertNull(updated.captured.completedAt)
        assertEquals(OBSERVED.atOffset(ZoneOffset.UTC), updated.captured.observedAt)
        assertEquals(OffsetDateTime.now(clock), updated.captured.updatedAt)
        assertEquals(stored.spec, updated.captured.spec)
        verify(exactly = 0) { runs.insert(any()) }
    }

    @Test
    fun `applyStatus skips older generations equal or older observations terminal runs and unknown phases`() {
        val olderGeneration = run(phase = AgentPhase.RUNNING, generation = 3, observedAt = OBSERVED.minusSeconds(60))
        every { runs.findByCrUid(olderGeneration.crUid!!) } returns olderGeneration
        service.applyStatus(event(uid = olderGeneration.crUid!!, phase = "SUCCESS", generation = 2))

        val sameObservation = run(phase = AgentPhase.RUNNING, generation = 1, observedAt = OBSERVED)
        every { runs.findByCrUid(sameObservation.crUid!!) } returns sameObservation
        service.applyStatus(event(uid = sameObservation.crUid!!, phase = "SUCCESS", generation = 1))

        val laterObservation = run(phase = AgentPhase.RUNNING, generation = 1, observedAt = OBSERVED.plusSeconds(1))
        every { runs.findByCrUid(laterObservation.crUid!!) } returns laterObservation
        service.applyStatus(event(uid = laterObservation.crUid!!, phase = "SUCCESS", generation = 1))

        val terminal = run(phase = AgentPhase.CANCELLED, generation = 1, observedAt = null)
        every { runs.findByCrUid(terminal.crUid!!) } returns terminal
        service.applyStatus(event(uid = terminal.crUid!!, phase = "RUNNING", generation = 5))

        every { runs.findByCrUid("uid-weird") } returns null
        service.applyStatus(event(uid = "uid-weird", phase = "EXPLODED", generation = 1))

        verify(exactly = 0) { runs.updateStatus(any()) }
        verify(exactly = 0) { runs.update(any()) }
        verify(exactly = 0) { runs.insert(any()) }
    }

    private fun run(phase: AgentPhase, generation: Long? = 1, observedAt: Instant? = null): AgentRun {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now(clock).minusMinutes(5)
        return AgentRun(
            id = id,
            kind = AgentKind.CODE_REVIEW,
            source = RunSource.API,
            repositoryId = repositoryId,
            crName = "cr-$id",
            crUid = "uid-$id",
            generation = generation,
            executionId = null,
            phase = phase,
            reason = null,
            message = null,
            spec = mapper.createObjectNode().put("branch", "feature/x"),
            createdAt = now,
            startedAt = null,
            completedAt = null,
            observedAt = observedAt?.atOffset(ZoneOffset.UTC),
            updatedAt = now,
        )
    }

    private fun event(
        uid: String,
        phase: String,
        generation: Long,
        reason: String? = null,
        message: String? = null,
    ): CodeReviewStatusChangedEvent = CodeReviewStatusChangedEvent(
        reviewName = "review-x",
        reviewNamespace = "sift-dev",
        reviewUid = uid,
        generation = generation,
        executionId = "$uid:$generation",
        repositoryUrl = "https://example.org/ext.git",
        branch = "feature/ext",
        baseBranch = "main",
        commitSha = "b".repeat(SHA_LENGTH),
        pullRequest = null,
        phase = phase,
        reason = reason,
        message = message,
        startedAt = STARTED,
        completedAt = null,
        observedAt = OBSERVED,
    )

    companion object {
        private const val SHA_LENGTH = 40
        private val STARTED: Instant = Instant.parse("2026-09-07T09:58:00Z")
        private val OBSERVED: Instant = Instant.parse("2026-09-07T09:59:00Z")
    }
}
