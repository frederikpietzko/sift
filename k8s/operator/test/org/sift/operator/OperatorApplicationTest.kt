package org.sift.operator

import io.fabric8.kubernetes.client.KubernetesClient
import io.javaoperatorsdk.operator.Operator
import io.javaoperatorsdk.operator.springboot.starter.CRDApplier
import io.javaoperatorsdk.operator.springboot.starter.OperatorConfigurationProperties
import io.javaoperatorsdk.operator.springboot.starter.OperatorStarter
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class OperatorApplicationTest {
    @Test
    fun `application scans properties and uses starter managed operator and client`() {
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Application::class.java)
            .withSystemProperties("kubernetes.disable.autoConfig=true")
            .withPropertyValues(
                "sift.operator.namespace=sift-test",
                "sift.operator.review.image=registry.example.org/review:wiring-test",
            )
            .run { context ->
                assertNull(context.startupFailure)
                assertEquals(1, context.getBeansOfType(Operator::class.java).size)
                assertEquals(1, context.getBeansOfType(KubernetesClient::class.java).size)
                assertNotNull(context.getBean(OperatorStarter::class.java))
                assertSame(CRDApplier.NOOP, context.getBean(CRDApplier::class.java))
                assertFalse(context.getBean(OperatorConfigurationProperties::class.java).crd.isApplyOnStartup)
                assertEquals("sift-test", context.getBean(OperatorProperties::class.java).namespace)
                val controller = context.getBean(Operator::class.java).registeredControllers.single()
                assertEquals(setOf("sift-test"), controller.configuration.effectiveNamespaces)
                assertNotNull(context.getBean(CodeReviewReconciler::class.java))
                assertNotNull(context.getBean(ReviewConfigMapDependent::class.java))
                assertNotNull(context.getBean(ReviewJobDependent::class.java))
            }
    }
}
