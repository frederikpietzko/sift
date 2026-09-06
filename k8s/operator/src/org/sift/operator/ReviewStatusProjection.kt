package org.sift.operator

import io.fabric8.kubernetes.api.model.ConditionBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import org.sift.crds.CodeReview
import org.sift.crds.Phase
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ReviewStatusProjection {
    fun project(resource: CodeReview, observation: ReviewObservation): CodeReview.Status {
        val outcome = outcome(observation)
        val result = status(resource, observation, outcome.first, outcome.second)
        val started = observation.pods.flatMap { it.status?.containerStatuses.orEmpty() }.mapNotNull {
            it.state?.running?.startedAt ?: it.state?.terminated?.startedAt
        }.minOrNull()
        val completed = observation.job?.status?.completionTime
        return result.copy(
            startedAt = result.startedAt ?: started,
            completedAt = if (result.phase == Phase.SUCCESS) completed ?: result.completedAt else result.completedAt,
        )
    }

    private fun outcome(observation: ReviewObservation): Pair<Phase, String> {
        val conditions = observation.job?.status?.conditions.orEmpty()
        val failed = conditions.firstOrNull { it.type == "Failed" && it.status == "True" }
        val complete = conditions.any { it.type == "Complete" && it.status == "True" }
        return when {
            failed != null -> Phase.FAILED to
                if (failed.reason == "DeadlineExceeded") "DeadlineExceeded" else "ExecutionFailed"
            complete -> Phase.SUCCESS to "Completed"
            else -> pendingOutcome(observation)
        }
    }

    private fun pendingOutcome(observation: ReviewObservation): Pair<Phase, String> {
        val running = observation.pods.any { pod -> pod.status?.containerStatuses.orEmpty().any {
            it.state?.running != null || it.state?.terminated?.startedAt != null
        } }
        val waiting = observation.pods.flatMap { it.status?.containerStatuses.orEmpty() }
            .mapNotNull { it.state?.waiting?.reason }
        val unscheduled = observation.pods.any { pod -> pod.status?.conditions.orEmpty().any {
            it.type == "PodScheduled" && it.status == "False"
        } } || observation.job?.status?.conditions.orEmpty().any { it.type == "FailureTarget" && it.status == "True" }
        return when {
            running -> Phase.RUNNING to "Executing"
            waiting.any { it in setOf("ImagePullBackOff", "ErrImagePull", "InvalidImageName") } ->
                Phase.PENDING to "ImagePullFailed"
            unscheduled -> Phase.PENDING to "SchedulingFailed"
            waiting.isNotEmpty() -> Phase.PENDING to "ContainerWaiting"
            else -> Phase.PENDING to "Scheduled"
        }
    }

    fun status(
        resource: CodeReview,
        observation: ReviewObservation,
        phase: Phase,
        reason: String,
    ): CodeReview.Status {
        val execution = ReviewExecution(resource)
        val previous = resource.status?.takeIf { it.executionId == execution.executionId }
        val now = Instant.now().toString()
        val message = messages.getValue(reason)
        val conditionStatus = if (phase == Phase.SUCCESS) "True" else "False"
        val oldCondition = previous?.conditions?.singleOrNull()
        val condition = ConditionBuilder().withType("Ready").withStatus(conditionStatus)
            .withReason(reason).withMessage(message).withObservedGeneration(execution.generation)
            .withLastTransitionTime(oldCondition?.takeIf { it.status == conditionStatus && it.reason == reason }
                ?.lastTransitionTime ?: now).build()
        fun reference(actual: HasMetadata?): CodeReview.ResourceReference? = actual?.metadata?.let {
            CodeReview.ResourceReference(name = it.name, uid = requireNotNull(it.uid))
        }
        return CodeReview.Status(
            phase = phase,
            message = message,
            observedGeneration = execution.generation,
            executionId = execution.executionId,
            commitSha = resource.spec?.commitSha,
            jobRef = previous?.jobRef ?: reference(observation.job),
            configMapRef = previous?.configMapRef ?: reference(observation.configMap),
            startedAt = previous?.startedAt,
            completedAt = previous?.completedAt ?: now.takeIf { phase in setOf(Phase.SUCCESS, Phase.FAILED) },
            conditions = listOf(condition),
        )
    }
}

private val messages = mapOf(
    "Completed" to "Review Job completed successfully",
    "Executing" to "Review container has started",
    "Scheduled" to "Waiting for the review container to start",
    "ImagePullFailed" to "Review image cannot be pulled",
    "SchedulingFailed" to "Kubernetes cannot schedule or finish the review Job",
    "ContainerWaiting" to "Review container is waiting for runtime configuration",
    "DeadlineExceeded" to "Review Job exceeded its execution deadline",
    "ExecutionFailed" to "Review Job failed; automatic whole-Job retries are disabled",
    "CancellationInProgress" to "Waiting for superseded Jobs and Pods to disappear",
    "ResourcesLost" to "An established execution resource is missing, deleting, or has a different UID",
    "ConfigurationError" to "Review specification or trusted execution configuration is invalid",
    "ResourceConflict" to "An execution name is occupied by a resource with conflicting ownership",
)
