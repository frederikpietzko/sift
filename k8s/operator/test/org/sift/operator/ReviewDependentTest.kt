package org.sift.operator

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.processing.dependent.Updater
import org.sift.crds.CodeReview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ReviewDependentTest {
    private val review = reviewFixture()
    private val properties = propertiesFixture()
    private val resources = ReviewResources(properties, ReviewConfiguration(properties))
    private val context = mockk<Context<CodeReview>>(relaxed = true)

    @Test
    fun `SDK reconciliation preserves existing Job and ConfigMap after operator configuration changes`() {
        val existingJob = resources.job(review)
        val existingConfig = resources.configMap(review)
        val originalConfig = existingConfig.data.toMap()
        every {
            context.client.resources(Job::class.java).inNamespace("sift-test")
                .withName(ReviewExecution(review).name).get()
        } returns existingJob
        every {
            context.client.resources(ConfigMap::class.java).inNamespace("sift-test")
                .withName(ReviewExecution(review).name).get()
        } returns existingConfig
        val changed = properties.copy(
            review = properties.review.copy(image = "registry.example.org/review@sha256:${"b".repeat(64)}"),
            services = properties.services.copy(model = "changed-model"),
        )
        val changedResources = ReviewResources(changed, ReviewConfiguration(changed))
        val job = ReviewJobDependent(changedResources)
        val config = ReviewConfigMapDependent(changedResources)
        repeat(3) {
            config.reconcile(review, context)
            job.reconcile(review, context)
        }
        assertEquals(properties.review.image, existingJob.spec.template.spec.containers.single().image)
        assertEquals(originalConfig, existingConfig.data)
        assertFalse(Updater::class.java.isAssignableFrom(job.javaClass))
        assertFalse(Updater::class.java.isAssignableFrom(config.javaClass))
        verify(exactly = 0) { context.resourceOperations() }
    }

    @Test
    fun `a same name resource owned by another UID is never adopted`() {
        val foreign = resources.job(review)
        foreign.metadata.ownerReferences.single().uid = "foreign-uid"
        every {
            context.client.resources(Job::class.java).inNamespace("sift-test")
                .withName(ReviewExecution(review).name).get()
        } returns foreign
        assertFailsWith<IllegalArgumentException> { ReviewJobDependent(resources).reconcile(review, context) }
        verify(exactly = 0) { context.resourceOperations() }
    }
}
