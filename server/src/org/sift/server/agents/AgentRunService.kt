package org.sift.server.agents

import org.sift.events.CodeReviewStatusChangedEvent
import org.sift.server.api.ConflictException
import org.sift.server.api.NotFoundException
import org.sift.server.repositories.RepositoryService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionOperations
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Use cases around agent runs. [create] deliberately does not run inside one transaction: the row is
 * committed as `CREATED` before the CR is applied so a status event arriving immediately after the apply
 * always finds it, and an apply failure is recorded as `FAILED` instead of rolling the run away.
 */
@Service
class AgentRunService(
    private val runs: AgentRunRepository,
    adapters: List<AgentKindAdapter>,
    private val repositories: RepositoryService,
    private val transactions: TransactionOperations,
    private val mapper: JsonMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(AgentRunService::class.java)
    private val adapters: Map<AgentKind, AgentKindAdapter> = adapters.associateBy { it.kind }

    fun create(request: CreateAgentRunRequest): AgentRun {
        val adapter = adapterFor(request.kind)
        val created = transactions.execute { persistCreated(adapter, request) }
        val applied = runCatching { adapter.apply(created, request) }.getOrElse { exception ->
            log.warn("Applying {} for run {} failed", request.kind, created.id, exception)
            transactions.execute {
                runs.update(
                    created.copy(
                        phase = AgentPhase.FAILED,
                        reason = REASON_APPLY_FAILED,
                        message = exception.message,
                        completedAt = now(),
                        updatedAt = now(),
                    ),
                )
            }
            throw exception
        }
        return transactions.execute {
            runs.update(created.copy(crName = applied.crName, crUid = applied.crUid, updatedAt = now()))
        }
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): AgentRun = find(id)

    @Transactional(readOnly = true)
    fun list(filter: AgentRunFilter, page: Int, size: Int): Page<AgentRun> =
        runs.list(filter, page.coerceAtLeast(0), size.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE))

    @Transactional
    fun cancel(id: UUID): AgentRun {
        val run = find(id)
        if (run.phase.terminal) {
            throw ConflictException("Agent run $id is already ${run.phase}")
        }
        adapterFor(run.kind).delete(run)
        return runs.update(
            run.copy(
                phase = AgentPhase.CANCELLED,
                reason = REASON_CANCELLED,
                message = null,
                completedAt = now(),
                updatedAt = now(),
            ),
        )
    }

    /**
     * Upserts the run behind a `CodeReview` status event. Unknown CR UIDs create an `EXTERNAL` run; known
     * runs are only advanced when the event is newer (by generation, then `observedAt`) and the stored
     * phase is not terminal.
     */
    @Transactional
    fun applyStatus(event: CodeReviewStatusChangedEvent) {
        val phase = AgentPhase.fromName(event.phase)
        if (phase == null) {
            log.warn("Ignoring status event for {} with unknown phase {}", event.reviewUid, event.phase)
            return
        }
        val stored = runs.findByCrUid(event.reviewUid)
        when {
            stored == null -> runs.insert(externalRun(event, phase))
            stored.phase.terminal -> log.debug("Run {} is terminal; ignoring status event", stored.id)
            event.isStaleFor(stored) -> log.debug("Run {} already has a newer status; ignoring event", stored.id)
            else -> runs.updateStatus(
                stored.copy(
                    phase = phase,
                    reason = event.reason,
                    message = event.message,
                    generation = event.generation,
                    executionId = event.executionId,
                    startedAt = event.startedAt?.toOffset(),
                    completedAt = event.completedAt?.toOffset(),
                    observedAt = event.observedAt.toOffset(),
                    updatedAt = now(),
                ),
            )
        }
    }

    private fun persistCreated(adapter: AgentKindAdapter, request: CreateAgentRunRequest): AgentRun {
        val repository = repositories.get(request.repositoryId)
        val id = UUID.randomUUID()
        val now = now()
        val spec = CodeReviewRunSpec(
            repositoryUrl = repository.url,
            branch = request.branch,
            baseBranch = request.baseBranch,
            commitSha = request.commitSha,
            pullRequest = request.pullRequest,
        )
        return runs.insert(
            AgentRun(
                id = id,
                kind = request.kind,
                source = RunSource.API,
                repositoryId = repository.id,
                crName = adapter.resourceName(id),
                crUid = null,
                generation = null,
                executionId = null,
                phase = AgentPhase.CREATED,
                reason = null,
                message = null,
                spec = mapper.valueToTree(spec),
                createdAt = now,
                startedAt = null,
                completedAt = null,
                observedAt = null,
                updatedAt = now,
            ),
        )
    }

    private fun externalRun(event: CodeReviewStatusChangedEvent, phase: AgentPhase): AgentRun {
        val now = now()
        val spec = CodeReviewRunSpec(
            repositoryUrl = event.repositoryUrl,
            branch = event.branch,
            baseBranch = event.baseBranch,
            commitSha = event.commitSha,
            pullRequest = event.pullRequest,
        )
        return AgentRun(
            id = UUID.randomUUID(),
            kind = AgentKind.CODE_REVIEW,
            source = RunSource.EXTERNAL,
            repositoryId = null,
            crName = event.reviewName,
            crUid = event.reviewUid,
            generation = event.generation,
            executionId = event.executionId,
            phase = phase,
            reason = event.reason,
            message = event.message,
            spec = mapper.valueToTree(spec),
            createdAt = now,
            startedAt = event.startedAt?.toOffset(),
            completedAt = event.completedAt?.toOffset(),
            observedAt = event.observedAt.toOffset(),
            updatedAt = now,
        )
    }

    private fun adapterFor(kind: AgentKind): AgentKindAdapter =
        adapters[kind] ?: throw IllegalArgumentException("No adapter registered for agent kind $kind")

    private fun find(id: UUID): AgentRun = runs.findById(id) ?: throw NotFoundException("Agent run $id not found")

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock)

    companion object {
        const val REASON_APPLY_FAILED = "ApplyFailed"
        const val REASON_CANCELLED = "CancelledByUser"
        const val MIN_PAGE_SIZE = 1
        const val MAX_PAGE_SIZE = 200
    }
}

private fun Instant.toOffset(): OffsetDateTime = atOffset(ZoneOffset.UTC)

/** True when [stored] already reflects this event's generation/observation (or a later one). */
private fun CodeReviewStatusChangedEvent.isStaleFor(stored: AgentRun): Boolean {
    val olderGeneration = stored.generation?.let { generation < it } ?: false
    val notNewerObservation = stored.observedAt?.let { !observedAt.toOffset().isAfter(it) } ?: false
    return olderGeneration || notNewerObservation
}
