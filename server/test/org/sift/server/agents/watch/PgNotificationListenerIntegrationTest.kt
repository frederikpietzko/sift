package org.sift.server.agents.watch

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.awaitility.Awaitility.await
import org.sift.server.agents.AgentPhase
import org.sift.server.agents.AgentRun
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The trigger → `LISTEN` → [AgentRunEvents] path against a real Postgres, including killed listener sessions.
 * Other cached Spring contexts in the same JVM may hold their own `sift-server-watch` session on the shared
 * container, so the tests reason about sets of pids rather than a single one.
 */
class PgNotificationListenerIntegrationTest : WatchIntegrationTest() {
    @Autowired
    private lateinit var events: AgentRunEvents

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `a committed insert and update are published as UPDATED events`() = runBlocking {
        awaitListener()

        val inserted = awaitEvent { insertRun() }
        assertEquals(AgentRunEventType.UPDATED, inserted.type)
        assertEquals(AgentPhase.CREATED, inserted.run.phase)

        val advanced = awaitEvent { advance(assertRun(inserted.run.id), AgentPhase.RUNNING) }
        assertEquals(AgentPhase.RUNNING, advanced.run.phase)
        assertEquals(inserted.run.id, advanced.run.id)
    }

    @Test
    fun `the listener reconnects after its backend is terminated and keeps publishing`() = runBlocking {
        val oldPids = awaitListener()

        jdbcTemplate.queryForList(
            "select pg_terminate_backend(pid) from pg_stat_activity where application_name = ?",
            PgNotificationConnection.APPLICATION_NAME,
        )
        val newPids = await().atMost(RECONNECT_TIMEOUT)
            .until({ listenerPids() }) { pids -> pids.size >= oldPids.size && pids.none { it in oldPids } }
        assertTrue(newPids.isNotEmpty())

        val event = awaitEvent { insertRun() }
        assertEquals(AgentRunEventType.UPDATED, event.type)
    }

    /** Subscribes first, then commits [write] and waits for the event about the written run. */
    private suspend fun awaitEvent(write: () -> AgentRun): AgentRunEvent = withTimeout(EVENT_TIMEOUT) {
        var written: AgentRun? = null
        events.events
            .onSubscription { written = write() }
            .first { event -> written?.let { event.run.id == it.id && event.run.phase == it.phase } ?: false }
    }

    private fun listenerPids(): List<Int> = jdbcTemplate.queryForList(
        "select pid from pg_stat_activity where application_name = ?",
        Int::class.java,
        PgNotificationConnection.APPLICATION_NAME,
    ).filterNotNull()

    private fun assertRun(id: UUID) = requireNotNull(transactions.execute { runs.findById(id) }) { "run $id missing" }

    private fun awaitListener(): Set<Int> =
        await().atMost(RECONNECT_TIMEOUT).until({ listenerPids() }) { it.isNotEmpty() }.toSet()

    companion object {
        private val EVENT_TIMEOUT = 10.seconds
        private val RECONNECT_TIMEOUT = Duration.ofSeconds(15)
    }
}
