package org.sift.operator

import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import io.javaoperatorsdk.operator.processing.dependent.workflow.WorkflowBuilder
import io.javaoperatorsdk.operator.processing.event.source.EventSource
import org.sift.crds.CodeReview
import org.sift.crds.Phase
import org.springframework.stereotype.Component

@Component
@ControllerConfiguration(name = "codereview")
class CodeReviewReconciler(
    private val policy: ReviewRunPolicy,
    private val configMap: ReviewConfigMapDependent,
    private val job: ReviewJobDependent,
    private val projection: ReviewStatusProjection,
) : Reconciler<CodeReview> {
    private val workflow = WorkflowBuilder<CodeReview>()
        .addDependentResource(configMap)
        .addDependentResourceAndConfigure(job).dependsOn(configMap)
        .build()

    override fun prepareEventSources(context: EventSourceContext<CodeReview>): List<EventSource<*, CodeReview>> =
        listOf(configMap.initEventSource(context), job.initEventSource(context))

    override fun reconcile(resource: CodeReview, context: Context<CodeReview>): UpdateControl<CodeReview> = when {
        !policy.isLatest(resource, context) -> requeue()
        policy.terminal(resource) -> UpdateControl.noUpdate()
        else -> reconcileCurrent(resource, context)
    }

    private fun reconcileCurrent(resource: CodeReview, context: Context<CodeReview>): UpdateControl<CodeReview> {
        val observation = policy.observe(resource, context)
        return when {
            policy.cancelOlder(observation, context) ->
                report(resource, context, observation, Phase.PENDING, "CancellationInProgress")
            observation.conflict -> report(resource, context, observation, Phase.FAILED, "ResourceConflict")
            policy.resourcesLost(resource, observation) ->
                report(resource, context, observation, Phase.FAILED, "ResourcesLost")
            !validConfiguration(resource, context, observation) ->
                report(resource, context, observation, Phase.FAILED, "ConfigurationError")
            !policy.isLatest(resource, context) -> requeue()
            else -> {
                if (observation.job == null) {
                    workflow.reconcile(resource, context).throwAggregateExceptionIfErrorsPresent()
                }
                save(resource, context, projection.project(resource, policy.observe(resource, context)))
            }
        }
    }

    private fun validConfiguration(
        resource: CodeReview,
        context: Context<CodeReview>,
        observation: ReviewObservation,
    ): Boolean = try {
        ReviewExecution.validate(resource)
        if (observation.job == null) {
            if (observation.configMap == null) configMap.validateDesired(resource, context)
            job.validateDesired(resource, context)
        }
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun report(
        resource: CodeReview,
        context: Context<CodeReview>,
        observation: ReviewObservation,
        phase: Phase,
        reason: String,
    ): UpdateControl<CodeReview> = save(resource, context, projection.status(resource, observation, phase, reason))

    private fun save(
        resource: CodeReview,
        context: Context<CodeReview>,
        status: CodeReview.Status,
    ): UpdateControl<CodeReview> {
        if (resource.status != status && policy.isLatest(resource, context)) {
            resource.status = status
            // Status and spec share resourceVersion: a concurrent spec change must reject this write.
            context.client.resource(resource).lockResourceVersion(resource.metadata.resourceVersion).updateStatus()
        }
        return requeue()
    }

    private fun requeue(): UpdateControl<CodeReview> =
        UpdateControl.noUpdate<CodeReview>().rescheduleAfter(REQUEUE_MILLIS)
}

private const val REQUEUE_MILLIS = 5000L
