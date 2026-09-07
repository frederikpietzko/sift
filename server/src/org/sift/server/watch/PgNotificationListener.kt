package org.sift.server.watch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sift.server.config.ServerProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridges the `agent_runs_notify` trigger (`pg_notify('sift_agent_runs', {id, phase, updatedAt})`) into
 * [AgentRunEvents]. One coroutine per server instance holds a dedicated `LISTEN` connection, polls it and
 * hands every notification to [AgentRunNotificationHandler]. Connection loss is retried with exponential
 * backoff; the loop ends when the application context closes.
 *
 * `RedundantSuspendModifier` is suppressed because detekt's type resolution does not see `kotlinx.coroutines`
 * builders (`withContext`, `delay`) as suspending in this build and would flag every private suspend function.
 */
@Component
@Suppress("RedundantSuspendModifier")
class PgNotificationListener(
    private val connections: PgNotificationConnection.Factory,
    private val handler: AgentRunNotificationHandler,
    private val properties: ServerProperties,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(PgNotificationListener::class.java)
    private val current = AtomicReference<PgNotificationConnection?>()
    private var job: Job? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!properties.watch.enabled) {
            log.info("Agent run watch is disabled; not listening on {}", CHANNEL)
            return
        }
        if (job?.isActive == true) {
            return
        }
        job = scope.launch(CoroutineName("pg-notification-listener")) { listenForever() }
    }

    override fun destroy() {
        job?.cancel(CancellationException("Application context is closing"))
        current.getAndSet(null)?.closeQuietly()
    }

    private suspend fun listenForever() {
        var backoff = INITIAL_BACKOFF_MILLIS
        while (currentCoroutineContext().isActive) {
            try {
                withContext(ioDispatcher) { connections.open(CHANNEL) }.use { connection ->
                    current.set(connection)
                    log.info("Listening for agent run notifications on {}", CHANNEL)
                    backoff = INITIAL_BACKOFF_MILLIS
                    poll(connection)
                }
            } catch (exception: SQLException) {
                // destroy() closes the socket under a blocked poll; that failure is the shutdown itself, not an outage
                currentCoroutineContext().ensureActive()
                log.warn("Lost the {} notification connection; reconnecting in {} ms", CHANNEL, backoff, exception)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
            } finally {
                current.set(null)
            }
        }
    }

    /**
     * `getNotifications(timeout)` blocks for at most [POLL_TIMEOUT_MILLIS], which bounds how long cancellation
     * takes: `withContext` rethrows the cancellation as soon as the blocking call returns.
     */
    private suspend fun poll(connection: PgNotificationConnection) {
        while (currentCoroutineContext().isActive) {
            withContext(ioDispatcher) { connection.poll(POLL_TIMEOUT_MILLIS) }.forEach(handler::publish)
        }
    }

    private fun PgNotificationConnection.closeQuietly() {
        try {
            close()
        } catch (exception: SQLException) {
            log.debug("Closing the notification connection failed", exception)
        }
    }

    companion object {
        const val CHANNEL = "sift_agent_runs"
        private const val POLL_TIMEOUT_MILLIS = 1_000
        private const val INITIAL_BACKOFF_MILLIS = 500L
        private const val MAX_BACKOFF_MILLIS = 30_000L
    }
}
