package org.sift.operator

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import org.sift.crds.CodeReview
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun reviewFixture(): CodeReview = CodeReview().apply {
    metadata = ObjectMetaBuilder().withName("review.example").withNamespace("sift-test")
        .withUid("53eaed67-43a1-49ae-90c4-06ac4a8f0068").withGeneration(1L).build()
    spec = CodeReview.Spec(
        repositoryUrl = "https://github.com/example/repository.git",
        branch = "feature/review",
        baseBranch = "main",
        commitSha = "a".repeat(40),
        pullRequest = "1",
    )
}

internal fun propertiesFixture(): OperatorProperties = OperatorProperties(
    namespace = "sift-test",
    review = OperatorProperties.Review(image = "registry.example.org/review:test"),
    services = OperatorProperties.Services(
        modelBaseUrl = "http://model/wire/{proxyToken}/codex/openai/v1",
        model = "test-model",
        searxngUrl = "http://search:8080",
    ),
    secrets = OperatorProperties.Secrets(
        modelApiKey = OperatorProperties.SecretKeyReference(name = "model-secret", key = "key"),
        proxyToken = OperatorProperties.SecretKeyReference(name = "proxy-secret", key = "token"),
        rabbitmqPassword = OperatorProperties.SecretKeyReference(name = "rabbit-secret", key = "password"),
        gitToken = OperatorProperties.SecretKeyReference(name = "git-secret", key = "token"),
    ),
)

class ReviewResourcesTest {
    private val properties = propertiesFixture()
    private val resources = ReviewResources(properties, ReviewConfiguration(properties))

    @Test
    fun `execution names are bounded deterministic and generation and UID specific`() {
        val review = reviewFixture()
        review.metadata.name = "review.".repeat(35)
        review.metadata.generation = Long.MAX_VALUE
        val execution = ReviewExecution(review)
        assertTrue(execution.name.length <= 63)
        assertTrue(execution.name.matches(Regex("[a-z0-9]([-a-z0-9]*[a-z0-9])?")))
        assertEquals(execution.name, ReviewExecution(review).name)
        assertEquals("${review.metadata.uid}:${Long.MAX_VALUE}", execution.executionId)
        review.metadata.generation = 1L
        assertNotEquals(execution.name, ReviewExecution(review).name)
        val previous = ReviewExecution(review).name
        review.metadata.uid = "53eaed67-43a1-49ae-90c4-06ac4a8f0069"
        assertNotEquals(previous, ReviewExecution(review).name)
    }

    @Test
    fun `ConfigMap is an owned immutable literal YAML snapshot with explicit property mapping`() {
        val review = reviewFixture()
        review.spec = review.spec.copy(branch = "feature/'quoted:#value")
        val config = resources.configMap(review)
        val values = Yaml().load<Map<String, Any>>(config.data.getValue("application.yaml"))
        assertEquals(true, config.immutable)
        assertEquals(review.spec.branch, values["sift.review.branch"])
        assertEquals(review.spec.repositoryUrl, values["sift.review.repository-url"])
        assertEquals("main", values["sift.review.base-branch"])
        assertEquals("1", values["sift.review.pull-request"])
        assertEquals("a".repeat(40), values["sift.review.commit-sha"])
        assertEquals(ReviewExecution(review).executionId, values["sift.review.execution-id"])
        assertEquals("http://model/wire/\${SIFT_MODEL_PROXY_TOKEN}/codex/openai/v1", values["spring.ai.openai.base-url"])
        assertEquals("test-model", values["spring.ai.openai.chat.options.model"])
        assertEquals("http://search:8080", values["sift.tools.web-search.base-url"])
        assertEquals(5672, values["spring.rabbitmq.port"])
        assertFalse(values.keys.any { it.contains("password") || it.contains("api-key") || it.contains("auth-token") })
        assertFalse(config.data.toString().contains("-secret"))
        ReviewExecution(review).requireOwned(config)
        review.spec = review.spec.copy(pullRequest = null)
        assertFalse(resources.configMap(review).data.getValue("application.yaml").contains("pull-request"))
    }

    @Test
    fun `Job is bounded nonroot read only and has no whole Job retries or API token`() {
        val review = reviewFixture()
        val job = resources.job(review)
        val pod = job.spec.template.spec
        val container = pod.containers.single()
        assertEquals(properties.review.image, container.image)
        assertEquals(0, job.spec.backoffLimit)
        assertEquals(3600L, job.spec.activeDeadlineSeconds)
        assertEquals(1, job.spec.parallelism)
        assertEquals("Never", pod.restartPolicy)
        assertEquals(false, pod.automountServiceAccountToken)
        assertEquals("sift-review", pod.serviceAccountName)
        assertEquals(true, pod.securityContext.runAsNonRoot)
        assertEquals(10001L, pod.securityContext.runAsUser)
        assertEquals(10001L, pod.securityContext.fsGroup)
        assertEquals("RuntimeDefault", pod.securityContext.seccompProfile.type)
        assertEquals(false, container.securityContext.allowPrivilegeEscalation)
        assertEquals(true, container.securityContext.readOnlyRootFilesystem)
        assertEquals(listOf("ALL"), container.securityContext.capabilities.drop)
        assertEquals("500m", container.resources.requests.getValue("cpu").toString())
        assertEquals("2Gi", container.resources.limits.getValue("memory").toString())
        assertEquals("2Gi", pod.volumes.single { it.name == "scratch" }.emptyDir.sizeLimit.toString())
        assertEquals(ReviewExecution(review).name, pod.volumes.single { it.name == "configuration" }.configMap.name)
        assertEquals(true, container.volumeMounts.single { it.name == "configuration" }.readOnly)
        assertEquals("/etc/sift/review", container.volumeMounts.single { it.name == "configuration" }.mountPath)
        assertEquals("file:/etc/sift/review/application.yaml", container.env.single {
            it.name == "SPRING_CONFIG_ADDITIONAL_LOCATION"
        }.value)
        assertFalse(container.env.any { it.name in setOf("SPRING_CONFIG_LOCATION", "SPRING_PROFILES_ACTIVE") })
        assertTrue(container.envFrom.isEmpty())
        assertNull(job.spec.ttlSecondsAfterFinished)
        ReviewExecution(review).requireOwned(job)
    }

    @Test
    fun `all credentials are required Secret key references and never literal values`() {
        val env = resources.job(reviewFixture()).spec.template.spec.containers.single().env
        val expected = mapOf(
            "OPENAI_API_KEY" to "model-secret",
            "SIFT_MODEL_PROXY_TOKEN" to "proxy-secret",
            "SPRING_RABBITMQ_PASSWORD" to "rabbit-secret",
            "SIFT_REVIEW_AUTH_TOKEN" to "git-secret",
        )
        expected.forEach { [name, secretName] ->
            val variable = env.single { it.name == name }
            assertNull(variable.value)
            assertEquals(secretName, variable.valueFrom.secretKeyRef.name)
            assertEquals(false, variable.valueFrom.secretKeyRef.optional)
        }
    }

    @Test
    fun `foreign ownership and generation are rejected even with deterministic names`() {
        val review = reviewFixture()
        val job = resources.job(review)
        job.metadata.ownerReferences.single().uid = "another-owner"
        assertFailsWith<IllegalArgumentException> { ReviewExecution(review).requireOwned(job) }
        val config = resources.configMap(review)
        config.metadata.labels[ReviewExecution.GENERATION_LABEL] = "2"
        assertFailsWith<IllegalArgumentException> { ReviewExecution(review).requireOwned(config) }
    }

    @Test
    fun `invalid specs credentials placeholders and budgets cannot produce resources`() {
        val review = reviewFixture()
        val original = review.spec
        listOf(
            original.copy(commitSha = "abc"),
            original.copy(baseBranch = ""),
            original.copy(branch = "\${OPENAI_API_KEY}"),
            original.copy(repositoryUrl = "https://token@example.org/repo"),
            original.copy(repositoryUrl = "https://example.org/repo?token=secret"),
        ).forEach {
            review.spec = it
            assertFailsWith<IllegalArgumentException> { resources.configMap(review) }
            assertFailsWith<IllegalArgumentException> { resources.job(review) }
        }
        val invalid = properties.copy(review = properties.review.copy(
            resources = properties.review.resources.copy(cpuRequest = "3", cpuLimit = "2"),
        ))
        assertFailsWith<IllegalArgumentException> {
            ReviewResources(invalid, ReviewConfiguration(invalid)).job(reviewFixture())
        }
    }
}