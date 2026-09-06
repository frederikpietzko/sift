package org.sift.events

import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant

/**
 * Published when a code review agent has finished reviewing a change set.
 */
data class CodeReviewCompletedEvent(
    val repositoryUrl: String,
    val branch: String,
    val baseBranch: String,
    val commitSha: String,
    val executionId: String,
    val pullRequest: String?,
    val summary: String,
    val findings: List<Finding>,
    val completedAt: Instant,
) : SiftEvent {
    @get:JsonIgnore
    override val routingKey: String
        get() = ROUTING_KEY

    companion object {
        const val ROUTING_KEY: String = "code-review.completed"
    }
}

/**
 * A single review finding reported by the code review agent.
 */
data class Finding(
    val file: String,
    val startLine: Int?,
    val endLine: Int?,
    val severity: Severity,
    val category: String?,
    val message: String,
    val suggestion: String?,
)

/**
 * Severity of a review [Finding].
 */
enum class Severity {
    BLOCKER,
    MAJOR,
    MINOR,
    INFO,
}
