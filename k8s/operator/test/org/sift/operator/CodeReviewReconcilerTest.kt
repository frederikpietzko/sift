package org.sift.operator

import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.NamespaceableResource
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.sift.crds.CodeReview
import org.sift.crds.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CodeReviewReconcilerTest {
    private val review = reviewFixture().apply { metadata.resourceVersion = "10" }
    private val context = mockk<Context<CodeReview>>(relaxed = true)
    private val policy = mockk<ReviewRunPolicy>()
    private val properties = propertiesFixture()
    private val resources = ReviewResources(properties, ReviewConfiguration(properties))
    private val config = ReviewConfigMapDependent(resources)
    private val job = ReviewJobDependent(resources)
    private val reconciler = CodeReviewReconciler(policy, config, job, ReviewStatusProjection())
    private val observation = ReviewObservation(
        job = resources.job(review).apply { metadata.uid = "job-uid" },
        configMap = resources.configMap(review).apply { metadata.uid = "config-uid" },
    )
    private val statusResource = mockk<NamespaceableResource<CodeReview>>(relaxed = true)

    init {
        every { context.client.resource(any<CodeReview>()) } returns statusResource
        every { statusResource.lockResourceVersion("10") } returns statusResource
        every { policy.isLatest(review, context) } returns true
        every { policy.terminal(review) } returns false
        every { policy.observe(review, context) } returns observation
        every { policy.cancelOlder(observation, context) } returns false
        every { policy.resourcesLost(review, observation) } returns false
    }

    @Test
    fun `stale event cannot write status or provision`() {
        every { policy.isLatest(review, context) } returns false
        reconciler.reconcile(review, context)
        assertNull(review.status?.executionId)
        verify(exactly = 0) { policy.observe(any(), any()) }
        verify(exactly = 0) { context.client }
    }

    @Test
    fun `a failed status write is retried using existing children and the same identity`() {
        val initialStatus = review.status
        every { statusResource.updateStatus() } throws
            KubernetesClientException("status conflict", 409, null)
        assertFailsWith<KubernetesClientException> { reconciler.reconcile(review, context) }
        val expected = review.status
        review.status = initialStatus
        every { statusResource.updateStatus() } returns review
        val restarted = CodeReviewReconciler(policy, config, job, ReviewStatusProjection())
        restarted.reconcile(review, context)
        assertEquals(expected.executionId, review.status.executionId)
        assertEquals(expected.jobRef, review.status.jobRef)
        assertEquals(expected.configMapRef, review.status.configMapRef)
        restarted.reconcile(review, context)
        verify(exactly = 2) { statusResource.lockResourceVersion("10") }
        verify(exactly = 2) { statusResource.updateStatus() }
        verify(exactly = 0) { context.resourceOperations() }
    }

    @Test
    fun `spec changes between observation and status write discard the old outcome`() {
        every { policy.isLatest(review, context) } returnsMany listOf(true, true, false)
        reconciler.reconcile(review, context)
        assertNull(review.status?.executionId)
        verify(exactly = 0) { context.client.resource(any<CodeReview>()) }
    }

    @Test
    fun `missing established resource records durable failure without calling workflow`() {
        every { policy.resourcesLost(review, observation) } returns true
        reconciler.reconcile(review, context)
        assertEquals(Phase.FAILED, review.status.phase)
        assertEquals("ResourcesLost", review.status.conditions.single().reason)
        verify(exactly = 0) { context.resourceOperations() }
    }

    @Test
    fun `terminal reconciliation never observes or recreates children`() {
        every { policy.terminal(review) } returns true
        reconciler.reconcile(review, context)
        verify(exactly = 0) { policy.observe(any(), any()) }
        verify(exactly = 0) { context.resourceOperations() }
    }

    @Test
    fun `cancellation precedes provisioning and reflects the latest generation`() {
        review.metadata.generation = 3L
        every { policy.cancelOlder(observation, context) } returns true
        reconciler.reconcile(review, context)
        assertEquals("CancellationInProgress", review.status.conditions.single().reason)
        assertEquals(3L, review.status.observedGeneration)
        verify(exactly = 0) { context.resourceOperations() }
    }

    @Test
    fun `ownership conflict does not bypass cancellation or adopt the foreign resource`() {
        val conflict = ReviewObservation(conflict = true)
        every { policy.observe(review, context) } returns conflict
        every { policy.cancelOlder(conflict, context) } returnsMany listOf(true, false)
        reconciler.reconcile(review, context)
        assertEquals("CancellationInProgress", review.status.conditions.single().reason)
        reconciler.reconcile(review, context)
        assertEquals("ResourceConflict", review.status.conditions.single().reason)
        assertNull(review.status.jobRef)
        assertNull(review.status.configMapRef)
        verify(exactly = 0) { context.resourceOperations() }
    }

    @Test
    fun `invalid spec fails with sanitized configuration condition`() {
        listOf("https://secret@example.org/repository", "https://example.org/%malformed").forEach { repository ->
            review.spec = review.spec.copy(repositoryUrl = repository)
            reconciler.reconcile(review, context)
            assertEquals("ConfigurationError", review.status.conditions.single().reason)
        }
        review.spec = null
        reconciler.reconcile(review, context)
        assertEquals("ConfigurationError", review.status.conditions.single().reason)
        assertNull(review.status.commitSha)
        verify(exactly = 0) { context.resourceOperations() }
    }

    @Test
    fun `generation changed after config creation cannot create an obsolete Job`() {
        every { context.client.resources(CodeReview::class.java).inNamespace(review.metadata.namespace)
            .withName(review.metadata.name).get() } returns reviewFixture().apply { metadata.resourceVersion = "11" }
        assertFailsWith<IllegalStateException> { job.create(resources.job(review), review, context) }
        verify(exactly = 0) { context.resourceOperations() }
    }
}