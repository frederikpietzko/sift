package org.sift.operator

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapListBuilder
import io.fabric8.kubernetes.api.model.DeletionPropagation
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodListBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobListBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder
import io.fabric8.kubernetes.client.dsl.Deletable
import io.fabric8.kubernetes.client.GracePeriodConfigurable
import io.fabric8.kubernetes.client.dsl.NamespaceableResource
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.sift.crds.CodeReview
import org.sift.crds.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewLifecycleTest {
    private val review = reviewFixture().apply { metadata.resourceVersion = "10" }
    private val policy = ReviewRunPolicy(propertiesFixture())
    private val projection = ReviewStatusProjection()
    private val resources = ReviewResources(propertiesFixture(), ReviewConfiguration(propertiesFixture()))
    private val job = resources.job(review).apply { metadata.uid = "job-uid"; metadata.resourceVersion = "20" }
    private val config = resources.configMap(review).apply { metadata.uid = "config-uid"; metadata.resourceVersion = "19" }
    private val context = mockk<Context<CodeReview>>(relaxed = true)

    @Test
    fun `only current UID and resource version may reconcile and deleting CRs never provision`() {
        every { context.client.resources(CodeReview::class.java).inNamespace("sift-test")
            .withName(review.metadata.name).get() } returns reviewFixture().apply { metadata.resourceVersion = "11" }
        assertFalse(policy.isLatest(review, context))
        every { context.client.resources(CodeReview::class.java).inNamespace("sift-test")
            .withName(review.metadata.name).get() } returns review
        assertTrue(policy.isLatest(review, context))
        review.metadata.deletionTimestamp = "2026-09-06T00:00:00Z"
        assertFalse(policy.isLatest(review, context))
    }

    @Test
    fun `foreground cancellation retains config until both Job and terminating Pods disappear`() {
        val deletion = mockk<GracePeriodConfigurable<Deletable>>(relaxed = true)
        val jobResource = mockk<NamespaceableResource<Job>>()
        val configResource = mockk<NamespaceableResource<ConfigMap>>(relaxed = true)
        every { context.client.resource(job) } returns jobResource
        every { context.client.resource(config) } returns configResource
        every { jobResource.lockResourceVersion(job.metadata.resourceVersion) } returns jobResource
        every { configResource.lockResourceVersion(config.metadata.resourceVersion) } returns configResource
        every { jobResource.withPropagationPolicy(DeletionPropagation.FOREGROUND) } returns deletion
        val pod = PodBuilder().withNewMetadata().withName("old-pod").withDeletionTimestamp("2026-09-06T00:00:00Z")
            .endMetadata().build()
        assertTrue(policy.cancelOlder(ReviewObservation(oldJobs = listOf(job), oldConfigs = listOf(config)), context))
        verify(exactly = 1) { jobResource.withPropagationPolicy(DeletionPropagation.FOREGROUND) }
        verify(exactly = 1) { jobResource.lockResourceVersion(job.metadata.resourceVersion) }
        verify(exactly = 1) { deletion.delete() }
        assertTrue(policy.cancelOlder(ReviewObservation(oldPods = listOf(pod), oldConfigs = listOf(config)), context))
        verify(exactly = 0) { configResource.delete() }
        assertTrue(policy.cancelOlder(ReviewObservation(oldConfigs = listOf(config)), context))
        verify(exactly = 1) { configResource.delete() }
        verify(exactly = 1) { configResource.lockResourceVersion(config.metadata.resourceVersion) }
        assertFalse(policy.cancelOlder(ReviewObservation(), context))
    }

    @Test
    fun `live owned children ignore stale informer state and unrelated resources`() {
        val foreign = resources.job(review).apply { metadata.name = "foreign"; metadata.ownerReferences.single().uid = "other" }
        every { context.client.resources(Job::class.java).inNamespace("sift-test").list() } returns
            JobListBuilder().withItems(job, foreign).build()
        every { context.client.resources(ConfigMap::class.java).inNamespace("sift-test").list() } returns
            ConfigMapListBuilder().withItems(config).build()
        every { context.client.pods().inNamespace("sift-test").list() } returns PodListBuilder().build()
        assertEquals(job, policy.observe(review, context).job)
        review.metadata.generation = 3L
        val observed = policy.observe(review, context)
        assertNull(observed.job)
        assertEquals(listOf(job), observed.oldJobs)
        assertEquals(listOf(config), observed.oldConfigs)
    }

    @Test
    fun `terminal identity survives resource deletion but a new generation is eligible`() {
        review.status = projection.status(review, ReviewObservation(job = job, configMap = config), Phase.SUCCESS, "Completed")
        assertTrue(policy.terminal(review))
        review.metadata.generation = 2L
        assertFalse(policy.terminal(review))
        assertFalse(policy.resourcesLost(review, ReviewObservation()))
    }

    @Test
    fun `lost or replaced established resources fail while unrecorded existing children are recovered`() {
        val observation = ReviewObservation(job = job, configMap = config)
        assertFalse(policy.resourcesLost(review, observation))
        review.status = projection.project(review, observation)
        assertTrue(policy.resourcesLost(review, observation.copy(job = null)))
        assertTrue(policy.resourcesLost(review, observation.copy(configMap = null)))
        job.metadata.uid = "replacement-uid"
        assertTrue(policy.resourcesLost(review, observation))
        val lost = projection.status(review, observation, Phase.FAILED, "ResourcesLost")
        assertEquals("job-uid", lost.jobRef?.uid)
    }

    @Test
    fun `orphan current Pods prevent recreation even before a status reference was persisted`() {
        val pod = PodBuilder().withNewMetadata().withLabels<String, String>(ReviewExecution(review).labels)
            .addNewOwnerReference().withKind("Job").withUid("deleted-job").endOwnerReference().endMetadata().build()
        every { context.client.resources(Job::class.java).inNamespace("sift-test").list() } returns
            JobListBuilder().build()
        every { context.client.resources(ConfigMap::class.java).inNamespace("sift-test").list() } returns
            ConfigMapListBuilder().withItems(config).build()
        every { context.client.pods().inNamespace("sift-test").list() } returns PodListBuilder().withItems(pod).build()
        assertTrue(policy.resourcesLost(review, policy.observe(review, context)))
        config.metadata.deletionTimestamp = "2026-09-06T00:00:00Z"
        assertTrue(policy.resourcesLost(review, ReviewObservation(configMap = config)))
    }

    @Test
    fun `only actual container execution starts RUNNING and only complete Job succeeds`() {
        job.status = JobStatusBuilder().withActive(1).withSucceeded(1).build()
        val observation = ReviewObservation(job = job, configMap = config)
        assertEquals(Phase.PENDING, projection.project(review, observation).phase)
        val pod = PodBuilder().withNewStatus().addNewContainerStatus().withName("review")
            .withNewState().withNewRunning().withStartedAt("2026-09-06T00:00:00Z").endRunning().endState()
            .endContainerStatus().endStatus().build()
        review.status = projection.project(review, observation.copy(pods = listOf(pod)))
        assertEquals(Phase.RUNNING, review.status.phase)
        assertEquals("2026-09-06T00:00:00Z", review.status.startedAt)
        job.status = JobStatusBuilder().withCompletionTime("2026-09-06T00:01:00Z")
            .addNewCondition().withType("Complete").withStatus("True").endCondition().build()
        val result = projection.project(review, observation)
        assertEquals(Phase.SUCCESS, result.phase)
        assertEquals(review.status.startedAt, result.startedAt)
        assertEquals("2026-09-06T00:01:00Z", result.completedAt)
    }

    @Test
    fun `waiting scheduling deadline and execution failures have stable sanitized conditions`() {
        val pod = PodBuilder().withNewStatus().addNewContainerStatus().withName("review")
            .withNewState().withNewWaiting().withReason("ImagePullBackOff").withMessage("sensitive detail")
            .endWaiting().endState().endContainerStatus().endStatus().build()
        val observation = ReviewObservation(job = job, configMap = config, pods = listOf(pod))
        review.status = projection.project(review, observation)
        assertEquals("ImagePullFailed", review.status.conditions.single().reason)
        assertEquals(review.status, projection.project(review, observation))
        assertFalse(review.status.toString().contains("sensitive detail"))
        pod.status.containerStatuses.clear()
        pod.status.conditions = listOf(io.fabric8.kubernetes.api.model.PodConditionBuilder()
            .withType("PodScheduled").withStatus("False").build())
        assertEquals("SchedulingFailed", projection.project(review, observation).conditions.single().reason)
        listOf("DeadlineExceeded", "BackoffLimitExceeded").forEach { reason ->
            job.status = JobStatusBuilder().addNewCondition().withType("Failed").withStatus("True")
                .withReason(reason).endCondition().build()
            val result = projection.project(review, observation)
            assertEquals(Phase.FAILED, result.phase)
            assertEquals(if (reason == "DeadlineExceeded") reason else "ExecutionFailed", result.conditions.single().reason)
            assertNotNull(result.completedAt)
        }
    }
}