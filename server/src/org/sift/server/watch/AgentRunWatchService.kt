package org.sift.server.watch

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withContext
import org.sift.server.agents.AgentKind
import org.sift.server.agents.AgentRun
import org.sift.server.agents.AgentRunFilter
import org.sift.server.agents.AgentRunRepository
import org.sift.server.agents.AgentRunResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Composes a watch stream: a `SNAPSHOT` of the matching runs (all of them, or only those written after
 * [WatchRequest.since] when the client resumes) followed by the live `UPDATED` events that match the filter.
 * The live subscription is established before the snapshot is loaded, so a write that lands in between is
 * buffered by the shared flow instead of being lost; the client may therefore see the same state twice.
 */
@Service
class AgentRunWatchService(
    private val runs: AgentRunRepository,
    private val transactions: TransactionOperations,
    private val events: AgentRunEvents,
    private val ioDispatcher: CoroutineDispatcher,
) {
    fun watch(request: WatchRequest): Flow<AgentRunEvent> = events.events
        .onSubscription {
            val snapshot = withContext(ioDispatcher) { transactions.execute { load(request) } }
            snapshot.forEach { run -> emit(AgentRunEvent(AgentRunEventType.SNAPSHOT, AgentRunResponse.from(run))) }
        }
        .filter { event -> request.matches(event.run) }

    private fun load(request: WatchRequest): List<AgentRun> {
        val agentId = request.agentId
        val filter = AgentRunFilter(kind = request.kind)
        val since = request.since
        return when {
            agentId != null -> listOfNotNull(runs.findById(agentId)).filter(request::matchesResume)
            since != null -> runs.findUpdatedSince(since.atOffset(ZoneOffset.UTC), filter, SNAPSHOT_LIMIT)
            else -> runs.list(filter, 0, SNAPSHOT_LIMIT).items
        }
    }

    companion object {
        const val SNAPSHOT_LIMIT = 200
    }
}

/** Filter of one watch stream; [since] is the resume point taken from the SSE `Last-Event-ID` header. */
data class WatchRequest(
    val agentId: UUID? = null,
    val kind: AgentKind? = null,
    val since: Instant? = null,
) {
    fun matches(run: AgentRunResponse): Boolean =
        (agentId == null || run.id == agentId) && (kind == null || run.kind == kind)

    /** Snapshot filter for a single run: the kind must match and, when resuming, the run must have changed since. */
    internal fun matchesResume(run: AgentRun): Boolean =
        (kind == null || run.kind == kind) && (since == null || run.updatedAt.toInstant().isAfter(since))
}
