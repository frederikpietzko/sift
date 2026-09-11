package org.sift.server.agents.persistence

import org.sift.server.PostgresIntegrationTest
import org.sift.server.agents.AgentKind
import org.sift.server.agents.AgentPhase
import org.sift.server.agents.AgentRun
import org.sift.server.agents.AgentRunFilter
import org.sift.server.agents.RunCreator
import org.sift.server.agents.RunSource
import org.sift.server.repositories.Repository
import org.sift.server.repositories.persistence.RepositoryRepository
import org.sift.server.users.User
import org.sift.server.users.persistence.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each test runs inside a Spring transaction that is rolled back, so the shared database stays clean. */
@Transactional
class AgentRunRepositoryTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var runs: AgentRunRepository

    @Autowired
    private lateinit var repositories: RepositoryRepository

    @Autowired
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val mapper = JsonMapper.builder().build()
    private val now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)

    @Test
    fun `insert and find round trip all columns`() {
        val repository = repository("alpha")
        val inserted = runs.insert(
            run(repository.id, AgentPhase.RUNNING, createdAt = now).copy(
                crUid = "uid-1",
                generation = 3,
                executionId = "uid-1:3",
                reason = "JobStarted",
                message = "started",
                startedAt = now.minusMinutes(1),
                observedAt = now.minusSeconds(30),
            ),
        )

        val found = assertNotNull(runs.findById(inserted.id))
        assertEquals(inserted.id, found.id)
        assertEquals(AgentKind.CODE_REVIEW, found.kind)
        assertEquals(RunSource.API, found.source)
        assertEquals(repository.id, found.repositoryId)
        assertEquals("cr-${inserted.id}", found.crName)
        assertEquals("uid-1", found.crUid)
        assertEquals(3L, found.generation)
        assertEquals("uid-1:3", found.executionId)
        assertEquals(AgentPhase.RUNNING, found.phase)
        assertEquals("JobStarted", found.reason)
        assertEquals("started", found.message)
        assertEquals("feature/x", found.spec["branch"].asString())
        assertTrue(now.isEqual(found.createdAt))
        assertTrue(now.minusMinutes(1).isEqual(assertNotNull(found.startedAt)))
        assertNull(found.completedAt)
        assertTrue(now.minusSeconds(30).isEqual(assertNotNull(found.observedAt)))
        assertTrue(now.isEqual(found.updatedAt))

        assertEquals(found.id, assertNotNull(runs.findByCrUid("uid-1")).id)
        assertNull(runs.findByCrUid("missing"))
        assertNull(runs.findById(UUID.randomUUID()))
    }

    @Test
    fun `external runs without repository are stored and cr uid is unique`() {
        val external = runs.insert(
            run(repositoryId = null, phase = AgentPhase.PENDING, createdAt = now)
                .copy(source = RunSource.EXTERNAL, crName = "review-ext", crUid = "uid-ext"),
        )
        val found = assertNotNull(runs.findById(external.id))
        assertEquals(RunSource.EXTERNAL, found.source)
        assertNull(found.repositoryId)
        assertEquals("review-ext", found.crName)

        assertFailsWith<Exception> {
            runs.insert(run(repositoryId = null, phase = AgentPhase.PENDING, createdAt = now).copy(crUid = "uid-ext"))
        }
    }

    @Test
    fun `update and updateStatus rewrite their columns and reject unknown ids`() {
        val repository = repository("bravo")
        val inserted = runs.insert(run(repository.id, AgentPhase.CREATED, createdAt = now))

        runs.update(inserted.copy(crUid = "uid-2", updatedAt = now.plusSeconds(1)))
        val applied = assertNotNull(runs.findById(inserted.id))
        assertEquals("uid-2", applied.crUid)
        assertEquals(AgentPhase.CREATED, applied.phase)
        assertTrue(now.plusSeconds(1).isEqual(applied.updatedAt))

        runs.updateStatus(
            applied.copy(
                phase = AgentPhase.SUCCESS,
                reason = "Completed",
                message = null,
                generation = 1,
                executionId = "uid-2:1",
                startedAt = now.plusSeconds(2),
                completedAt = now.plusSeconds(3),
                observedAt = now.plusSeconds(3),
                updatedAt = now.plusSeconds(4),
                crName = "ignored-by-update-status",
            ),
        )
        val finished = assertNotNull(runs.findById(inserted.id))
        assertEquals(AgentPhase.SUCCESS, finished.phase)
        assertEquals("Completed", finished.reason)
        assertEquals(1L, finished.generation)
        assertEquals("uid-2:1", finished.executionId)
        assertEquals("cr-${inserted.id}", finished.crName)
        assertTrue(now.plusSeconds(3).isEqual(assertNotNull(finished.completedAt)))
        assertTrue(now.plusSeconds(4).isEqual(finished.updatedAt))

        val ghost = inserted.copy(id = UUID.randomUUID())
        assertFailsWith<IllegalStateException> { runs.update(ghost) }
        assertFailsWith<IllegalStateException> { runs.updateStatus(ghost) }
    }

    @Test
    fun `list filters by kind phase and repository sorted newest first with pagination`() {
        // Other test classes commit runs (e.g. the consumer test); clear them inside this rolled-back transaction.
        jdbcTemplate.update("delete from review_results")
        jdbcTemplate.update("delete from agent_runs")
        val first = repository("charlie")
        val second = repository("delta")
        val oldest = runs.insert(run(first.id, AgentPhase.SUCCESS, createdAt = now.minusHours(3)))
        val middle = runs.insert(run(second.id, AgentPhase.RUNNING, createdAt = now.minusHours(2)))
        val newer = runs.insert(run(first.id, AgentPhase.RUNNING, createdAt = now.minusHours(1)))
        val newest = runs.insert(run(first.id, AgentPhase.PENDING, createdAt = now))

        val all = runs.list(AgentRunFilter(), page = 0, size = 10)
        assertEquals(listOf(newest.id, newer.id, middle.id, oldest.id), all.items.map { it.id })
        assertEquals(4L, all.total)
        assertEquals(0, all.page)
        assertEquals(10, all.size)

        val pageOne = runs.list(AgentRunFilter(), page = 1, size = 2)
        assertEquals(listOf(middle.id, oldest.id), pageOne.items.map { it.id })
        assertEquals(4L, pageOne.total)
        assertTrue(runs.list(AgentRunFilter(), page = 2, size = 2).items.isEmpty())

        val running = runs.list(AgentRunFilter(phase = AgentPhase.RUNNING), page = 0, size = 10)
        assertEquals(listOf(newer.id, middle.id), running.items.map { it.id })
        assertEquals(2L, running.total)

        val firstRepo = runs.list(AgentRunFilter(repositoryId = first.id), page = 0, size = 10)
        assertEquals(listOf(newest.id, newer.id, oldest.id), firstRepo.items.map { it.id })

        val combined = runs.list(
            AgentRunFilter(kind = AgentKind.CODE_REVIEW, phase = AgentPhase.RUNNING, repositoryId = second.id),
            page = 0,
            size = 10,
        )
        assertEquals(listOf(middle.id), combined.items.map { it.id })
        assertEquals(1L, combined.total)

        assertFailsWith<IllegalArgumentException> { runs.list(AgentRunFilter(), page = -1, size = 10) }
        assertFailsWith<IllegalArgumentException> { runs.list(AgentRunFilter(), page = 0, size = 0) }
    }

    @Test
    fun `created_by is persisted joined with the username and honoured by list and findUpdatedSince`() {
        jdbcTemplate.update("delete from review_results")
        jdbcTemplate.update("delete from agent_runs")
        val alice = user("alice")
        val bob = user("bob")
        val repository = repository("echo")
        val byAlice = runs.insert(
            run(repository.id, AgentPhase.RUNNING, createdAt = now.minusHours(2))
                .copy(createdBy = RunCreator(id = alice.id, username = "stale-name-is-ignored")),
        )
        val byBob = runs.insert(
            run(repository.id, AgentPhase.RUNNING, createdAt = now.minusHours(1))
                .copy(createdBy = RunCreator(id = bob.id, username = bob.username)),
        )
        val external = runs.insert(
            run(repositoryId = null, phase = AgentPhase.PENDING, createdAt = now).copy(source = RunSource.EXTERNAL),
        )

        assertEquals(RunCreator(alice.id, "alice"), assertNotNull(runs.findById(byAlice.id)).createdBy)
        assertEquals(RunCreator(bob.id, "bob"), assertNotNull(runs.findById(byBob.id)).createdBy)
        assertNull(assertNotNull(runs.findById(external.id)).createdBy)

        val all = runs.list(AgentRunFilter(), page = 0, size = 10)
        assertEquals(listOf(external.id, byBob.id, byAlice.id), all.items.map { it.id })
        assertEquals(3L, all.total)

        val alicesRuns = runs.list(AgentRunFilter(createdBy = alice.id), page = 0, size = 10)
        assertEquals(listOf(byAlice.id), alicesRuns.items.map { it.id })
        assertEquals(1L, alicesRuns.total)
        assertTrue(runs.list(AgentRunFilter(createdBy = UUID.randomUUID()), page = 0, size = 10).items.isEmpty())

        val since = now.minusHours(3)
        assertEquals(
            listOf(byBob.id),
            runs.findUpdatedSince(since, AgentRunFilter(createdBy = bob.id), limit = 10).map { it.id },
        )
        assertEquals(
            listOf(byAlice.id, byBob.id, external.id),
            runs.findUpdatedSince(since, AgentRunFilter(), limit = 10).map { it.id },
        )

        // unknown creator ids are rejected by the foreign key
        assertFailsWith<Exception> {
            runs.insert(
                run(repository.id, AgentPhase.CREATED, createdAt = now)
                    .copy(createdBy = RunCreator(id = UUID.randomUUID(), username = "ghost")),
            )
        }
    }

    @Test
    fun `hasActiveRuns only counts non terminal runs of the given repository`() {
        val busy = repository("busy")
        val idle = repository("idle")
        val other = repository("other")
        runs.insert(run(busy.id, AgentPhase.RUNNING, createdAt = now))
        runs.insert(run(idle.id, AgentPhase.SUCCESS, createdAt = now))
        runs.insert(run(idle.id, AgentPhase.FAILED, createdAt = now))
        runs.insert(run(idle.id, AgentPhase.CANCELLED, createdAt = now))
        runs.insert(run(other.id, AgentPhase.PENDING, createdAt = now))

        assertTrue(runs.hasActiveRuns(busy.id))
        assertFalse(runs.hasActiveRuns(idle.id))
        assertTrue(runs.hasActiveRuns(other.id))
        assertFalse(runs.hasActiveRuns(UUID.randomUUID()))
    }

    @Test
    fun `revision lineage is persisted resolved in both directions and cleared when the predecessor is deleted`() {
        val repository = repository("lineage")
        val predecessor = runs.insert(run(repository.id, AgentPhase.CANCELLED, createdAt = now.minusHours(1)))
        val successor = runs.insert(
            run(repository.id, AgentPhase.CREATED, createdAt = now).copy(supersedesRunId = predecessor.id),
        )

        assertEquals(predecessor.id, assertNotNull(runs.findById(successor.id)).supersedesRunId)
        assertNull(assertNotNull(runs.findById(successor.id)).supersededByRunId)
        assertEquals(successor.id, assertNotNull(runs.findById(predecessor.id)).supersededByRunId)
        assertEquals(successor.id, runs.findSupersededBy(predecessor.id))
        assertNull(runs.findSupersededBy(successor.id))

        assertTrue(runs.delete(predecessor.id))
        val orphaned = assertNotNull(runs.findById(successor.id))
        assertNull(orphaned.supersedesRunId)
    }

    private fun user(username: String): User = users.upsert(
        User(
            id = UUID.randomUUID(),
            issuer = "https://issuer.test/realms/repo-test",
            subject = "sub-$username",
            username = username,
            email = null,
            createdAt = now,
            lastSeenAt = now,
        ),
    )

    private fun run(repositoryId: UUID?, phase: AgentPhase, createdAt: OffsetDateTime): AgentRun {
        val id = UUID.randomUUID()
        return AgentRun(
            id = id,
            kind = AgentKind.CODE_REVIEW,
            source = RunSource.API,
            repositoryId = repositoryId,
            crName = "cr-$id",
            crUid = null,
            generation = null,
            executionId = null,
            phase = phase,
            reason = null,
            message = null,
            spec = mapper.createObjectNode().put("branch", "feature/x"),
            createdAt = createdAt,
            startedAt = null,
            completedAt = null,
            observedAt = null,
            updatedAt = createdAt,
        )
    }

    private fun repository(name: String): Repository {
        val id = UUID.randomUUID()
        return repositories.insert(
            Repository(
                id = id,
                name = name,
                url = "https://example.org/$name.git",
                token = null,
                secretName = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}
