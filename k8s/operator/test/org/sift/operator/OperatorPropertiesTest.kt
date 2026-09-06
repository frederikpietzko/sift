package org.sift.operator

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ByteArrayResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperatorPropertiesTest {
    private val runner = ApplicationContextRunner()
        .withInitializer(ConfigDataApplicationContextInitializer())
        .withUserConfiguration(PropertiesConfiguration::class.java)
        .withPropertyValues("sift.operator.namespace=sift-test")

    @Test
    fun `operator YAML accepts exact tag and digest image overrides`() {
        val images = listOf(
            "jbfpietzko/shift-code-review-agent:release-1",
            "registry.example.org:5000/team/review@sha256:${"a".repeat(64)}",
        )
        images.forEach { image ->
            withImageYaml(image).run { context ->
                assertNull(context.startupFailure)
                val properties = context.getBean(OperatorProperties::class.java)
                assertEquals(image, properties.review.image)
                val resources = ReviewResources(properties, ReviewConfiguration(properties))
                assertEquals(image, resources.job(reviewFixture()).spec.template.spec.containers.single().image)
                assertEquals("sift-test", properties.namespace)
                assertEquals(3600L, properties.review.deadlineSeconds)
                assertEquals("sift-review", properties.review.serviceAccount)
                assertEquals("2Gi", properties.review.resources.scratchSizeLimit)
                assertNull(properties.secrets.gitToken)
                assertEquals("none", context.environment.getProperty("spring.main.web-application-type"))
            }
        }
    }

    @Test
    fun `empty and whitespace images fail binding validation`() {
        listOf("", "   ").forEach { image ->
            withImageYaml(image).run { context ->
                val failure = assertNotNull(context.startupFailure)
                val messages = generateSequence(failure) { it.cause }.map { it.message.orEmpty() }.joinToString()
                assertTrue(messages.contains("review.image"), messages)
                assertTrue(messages.contains("must not be blank"), messages)
            }
        }
    }

    @Test
    fun `missing image fails instead of selecting an implicit image`() {
        runner.withPropertyValues("SIFT_REVIEW_IMAGE=").run { context ->
            assertNotNull(context.startupFailure)
        }
    }

    @Test
    fun `trusted service resource and secret references bind without secret contents`() {
        withImageYaml("registry.example.org/review:test")
            .withPropertyValues(
                "sift.operator.services.model-base-url=http://model.test/proxy/path",
                "sift.operator.services.searxng-url=http://searxng:8080",
                "sift.operator.services.rabbitmq-port=5673",
                "sift.operator.review.resources.memory-limit=3Gi",
                "sift.operator.secrets.rabbitmq-password.name=review-messaging",
                "sift.operator.secrets.rabbitmq-password.key=password",
            ).run { context ->
                assertNull(context.startupFailure)
                val properties = context.getBean(OperatorProperties::class.java)
                assertEquals("http://model.test/proxy/path", properties.services.modelBaseUrl)
                assertEquals("http://searxng:8080", properties.services.searxngUrl)
                assertEquals(5673, properties.services.rabbitmqPort)
                assertEquals("3Gi", properties.review.resources.memoryLimit)
                assertEquals(
                    OperatorProperties.SecretKeyReference(name = "review-messaging", key = "password"),
                    properties.secrets.rabbitmqPassword,
                )
            }
    }

    @Test
    fun `invalid namespace deadline and secret references fail startup`() {
        listOf(
            "sift.operator.namespace=ALL_NAMESPACES",
            "sift.operator.review.deadline-seconds=0",
            "sift.operator.review.deadline-seconds=86401",
            "sift.operator.secrets.git-token.name=",
        ).forEach { invalidProperty ->
            withImageYaml("registry.example.org/review:test")
                .withPropertyValues(
                    "sift.operator.secrets.git-token.name=review-git",
                    "sift.operator.secrets.git-token.key=token",
                    invalidProperty,
                )
                .run { context -> assertNotNull(context.startupFailure) }
        }
    }

    private fun withImageYaml(image: String): ApplicationContextRunner = runner.withInitializer { context ->
        val yaml = ByteArrayResource("sift:\n  operator:\n    review:\n      image: '$image'\n".toByteArray())
        YamlPropertySourceLoader().load("image-override", yaml).forEach {
            context.environment.propertySources.addFirst(it)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OperatorProperties::class)
    class PropertiesConfiguration
}
