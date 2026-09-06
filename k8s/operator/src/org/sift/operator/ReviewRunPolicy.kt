package org.sift.operator

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.DeletionPropagation
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.client.dsl.DeletableWithOptions
import io.javaoperatorsdk.operator.api.reconciler.Context
import org.sift.crds.CodeReview
import org.sift.crds.Phase
import org.springframework.stereotype.Component

@Component
class ReviewRunPolicy(private val properties: OperatorProperties) {
    fun isLatest(resource: CodeReview, context: Context<CodeReview>): Boolean {
        if (resource.metadata.namespace != properties.namespace || resource.metadata.deletionTimestamp != null) {
            return false
        }
        val latest = context.client.resources(CodeReview::class.java)
            .inNamespace(resource.metadata.namespace).withName(resource.metadata.name).get()
        return latest?.metadata?.uid == resource.metadata.uid &&
            latest.metadata.generation == resource.metadata.generation &&
            latest.metadata.resourceVersion == resource.metadata.resourceVersion &&
            latest.metadata.deletionTimestamp == null
    }

    fun observe(resource: CodeReview, context: Context<CodeReview>): ReviewObservation {
        val execution = ReviewExecution(resource)
        val jobs = context.client.resources(Job::class.java).inNamespace(execution.namespace).list().items
        val configs = context.client.resources(ConfigMap::class.java).inNamespace(execution.namespace).list().items
        val ownedJobs = jobs.filter { execution.owns(it) }
        val ownedConfigs = configs.filter { execution.owns(it) }
        val pods = context.client.pods().inNamespace(execution.namespace).list().items.filter { pod ->
            pod.metadata.labels?.get(ReviewExecution.OWNER_LABEL) == execution.uid ||
                pod.metadata.ownerReferences.any { owner -> ownedJobs.any { it.metadata.uid == owner.uid } }
        }
        val currentJob = jobs.singleOrNull { it.metadata.name == execution.name }
        val currentConfig = configs.singleOrNull { it.metadata.name == execution.name }
        val conflict = listOfNotNull(currentJob, currentConfig).any { actual ->
            !execution.owns(actual) || execution.labels.any { (key, value) ->
                actual.metadata.labels?.get(key) != value
            }
        }
        return ReviewObservation(
            job = currentJob.takeUnless { conflict },
            configMap = currentConfig.takeUnless { conflict },
            pods = pods.filter { pod ->
                if (currentJob == null) {
                    pod.metadata.labels?.get(ReviewExecution.GENERATION_LABEL) == execution.generation.toString()
                } else pod.metadata.ownerReferences.any { it.uid == currentJob.metadata.uid }
            },
            oldJobs = ownedJobs.filter { it.metadata.name != execution.name },
            oldConfigs = ownedConfigs.filter { it.metadata.name != execution.name },
            oldPods = pods.filter { pod ->
                pod.metadata.labels?.get(ReviewExecution.GENERATION_LABEL) != execution.generation.toString() ||
                    pod.metadata.ownerReferences.any { owner -> ownedJobs.any {
                        it.metadata.name != execution.name && it.metadata.uid == owner.uid
                    } }
            },
            conflict = conflict,
        )
    }

    fun cancelOlder(observation: ReviewObservation, context: Context<CodeReview>): Boolean {
        observation.oldJobs.filter { it.metadata.deletionTimestamp == null }.forEach {
            val deletion = context.client.resource(it).lockResourceVersion(it.metadata.resourceVersion)
            // Fabric8 7.8 narrows the lock result, but its operation supports both public interfaces.
            check(deletion is DeletableWithOptions) { "Version-locked foreground deletion is required" }
            deletion.withPropagationPolicy(DeletionPropagation.FOREGROUND).delete()
        }
        if (observation.oldJobs.isNotEmpty() || observation.oldPods.isNotEmpty()) return true
        observation.oldConfigs.forEach {
            context.client.resource(it).lockResourceVersion(it.metadata.resourceVersion).delete()
        }
        return observation.oldConfigs.isNotEmpty()
    }

    fun terminal(resource: CodeReview): Boolean =
        resource.status?.executionId == ReviewExecution(resource).executionId &&
            resource.status?.phase in setOf(Phase.SUCCESS, Phase.FAILED)

    fun resourcesLost(resource: CodeReview, observation: ReviewObservation): Boolean {
        val status = resource.status?.takeIf { it.executionId == ReviewExecution(resource).executionId }
        fun missing(reference: CodeReview.ResourceReference?, actual: HasMetadata?): Boolean =
            reference != null && reference.uid != actual?.metadata?.uid
        return missing(status?.jobRef, observation.job) || missing(status?.configMapRef, observation.configMap) ||
            observation.job?.metadata?.deletionTimestamp != null ||
            observation.configMap?.metadata?.deletionTimestamp != null ||
            (observation.job != null && observation.configMap == null) ||
            (observation.job == null && observation.pods.isNotEmpty())
    }
}

data class ReviewObservation(
    val job: Job? = null,
    val configMap: ConfigMap? = null,
    val pods: List<Pod> = emptyList(),
    val oldJobs: List<Job> = emptyList(),
    val oldConfigs: List<ConfigMap> = emptyList(),
    val oldPods: List<Pod> = emptyList(),
    val conflict: Boolean = false,
)
