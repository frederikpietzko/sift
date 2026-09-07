package org.sift.server

import org.sift.server.config.ServerProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ServerPropertiesTest {
    private val runner = ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration::class.java)

    @Test
    fun `binds defaults when only the encryption key is provided`() {
        runner.withPropertyValues("sift.server.encryption-key=c2VjcmV0").run { context ->
            assertNull(context.startupFailure)
            val properties = context.getBean(ServerProperties::class.java)
            assertEquals("sift-dev", properties.namespace)
            assertEquals("c2VjcmV0", properties.encryptionKey)
            assertEquals("sift-repo-", properties.secretPrefix)
            assertEquals(Duration.ofSeconds(15), properties.watch.heartbeat)
        }
    }

    @Test
    fun `binds overrides`() {
        runner.withPropertyValues(
            "sift.server.encryption-key=c2VjcmV0",
            "sift.server.namespace=sift-prod",
            "sift.server.secret-prefix=repo-",
            "sift.server.watch.heartbeat=30s",
        ).run { context ->
            assertNull(context.startupFailure)
            val properties = context.getBean(ServerProperties::class.java)
            assertEquals("sift-prod", properties.namespace)
            assertEquals("repo-", properties.secretPrefix)
            assertEquals(Duration.ofSeconds(30), properties.watch.heartbeat)
        }
    }

    @Test
    fun `missing encryption key and invalid namespace fail startup`() {
        listOf(
            arrayOf("sift.server.encryption-key="),
            arrayOf("sift.server.encryption-key=c2VjcmV0", "sift.server.namespace=Not_A_Label"),
        ).forEach { values ->
            runner.withPropertyValues(*values).run { context -> assertNotNull(context.startupFailure) }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ServerProperties::class)
    class PropertiesConfiguration
}
