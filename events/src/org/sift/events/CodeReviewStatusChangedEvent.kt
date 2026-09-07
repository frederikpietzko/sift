package org.sift.events

import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant

/**
 * Published by the operator whenever the status of a `CodeReview` custom resource changes.
 *
 * [phase] is carried as a plain string (`CREATED`, `PENDING`, `RUNNING`, `SUCCESS`, `FAILED`) so that
 * this module stays free of any Kubernetes dependency. [observedAt] is the operator's wall-clock time
 * of the status write and lets consumers discard events that arrive out of order.
 */
data class CodeReviewStatusChangedEvent(
    val reviewName: String,
    val reviewNamespace: String,
    val reviewUid: String,
    val generation: Long,
    val executionId: String?,
    val repositoryUrl: String,
    val branch: String,
    val baseBranch: String,
    val commitSha: String,
    val pullRequest: String?,
    val phase: String,
    val reason: String?,
    val message: String?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val observedAt: Instant,
) : SiftEvent {
    @get:JsonIgnore
    override val routingKey: String
        get() = ROUTING_KEY

    companion object {
        const val ROUTING_KEY: String = "code-review.status"
    }
}
