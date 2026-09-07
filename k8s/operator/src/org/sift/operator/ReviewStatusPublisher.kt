package org.sift.operator

import org.sift.crds.CodeReview
import org.sift.crds.Phase
import org.sift.events.CodeReviewStatusChangedEvent
import org.sift.messaging.EventPublisher
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpException
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Publishes a [CodeReviewStatusChangedEvent] after the operator has persisted a new `CodeReview` status.
 *
 * Publishing is best effort: the Kubernetes status write is the source of truth and a broker outage
 * must never fail or retry a reconciliation. Failures are logged and the reconcile loop continues.
 */
@Component
class ReviewStatusPublisher(
    private val eventPublisher: EventPublisher,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun publish(resource: CodeReview, status: CodeReview.Status) {
        val event = event(resource, status)
        try {
            eventPublisher.publish(event)
        } catch (exception: AmqpException) {
            log.warn(
                "Failed to publish status {} for CodeReview {}/{} (generation {}); the cluster status stays persisted",
                event.phase,
                event.reviewNamespace,
                event.reviewName,
                event.generation,
                exception,
            )
        }
    }

    fun event(resource: CodeReview, status: CodeReview.Status): CodeReviewStatusChangedEvent {
        val spec = resource.spec
        return CodeReviewStatusChangedEvent(
            reviewName = resource.metadata.name,
            reviewNamespace = resource.metadata.namespace.orEmpty(),
            reviewUid = resource.metadata.uid.orEmpty(),
            generation = status.observedGeneration ?: resource.metadata.generation ?: 0L,
            executionId = status.executionId,
            repositoryUrl = spec?.repositoryUrl.orEmpty(),
            branch = spec?.branch.orEmpty(),
            baseBranch = spec?.baseBranch.orEmpty(),
            commitSha = spec?.commitSha.orEmpty(),
            pullRequest = spec?.pullRequest,
            phase = (status.phase ?: Phase.CREATED).name,
            reason = status.conditions.firstOrNull { it.type == READY_CONDITION }?.reason,
            message = status.message,
            startedAt = status.startedAt?.let(::instantOrNull),
            completedAt = status.completedAt?.let(::instantOrNull),
            observedAt = clock.instant(),
        )
    }

    private fun instantOrNull(value: String): Instant? = try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }

    private companion object {
        const val READY_CONDITION = "Ready"
        val log = LoggerFactory.getLogger(ReviewStatusPublisher::class.java)
    }
}
