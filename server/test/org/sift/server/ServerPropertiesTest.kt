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
    fun `binds defaults when only the required keys are provided`() {
        runner.withPropertyValues(*REQUIRED).run { context ->
            assertNull(context.startupFailure)
            val properties = context.getBean(ServerProperties::class.java)
            assertEquals("sift-dev", properties.namespace)
            assertEquals("c2VjcmV0", properties.encryptionKey)
            assertEquals("sift-repo-", properties.secretPrefix)
            assertEquals(Duration.ofSeconds(15), properties.watch.heartbeat)
            assertEquals("sift-web", properties.auth.clientId)
            assertEquals(listOf("openid", "profile", "email"), properties.auth.scopes)
            assertEquals("preferred_username", properties.auth.claims.username)
            assertEquals("email", properties.auth.claims.email)
        }
    }

    @Test
    fun `binds overrides`() {
        runner.withPropertyValues(
            *REQUIRED,
            "sift.server.namespace=sift-prod",
            "sift.server.secret-prefix=repo-",
            "sift.server.watch.heartbeat=30s",
            "sift.server.auth.client-id=sift-spa",
            "sift.server.auth.scopes=openid,email",
            "sift.server.auth.claims.username=upn",
            "sift.server.auth.claims.email=mail",
        ).run { context ->
            assertNull(context.startupFailure)
            val properties = context.getBean(ServerProperties::class.java)
            assertEquals("sift-prod", properties.namespace)
            assertEquals("repo-", properties.secretPrefix)
            assertEquals(Duration.ofSeconds(30), properties.watch.heartbeat)
            assertEquals("sift-spa", properties.auth.clientId)
            assertEquals(listOf("openid", "email"), properties.auth.scopes)
            assertEquals("upn", properties.auth.claims.username)
            assertEquals("mail", properties.auth.claims.email)
        }
    }

    @Test
    fun `missing encryption key or client id and invalid namespace fail startup`() {
        listOf(
            arrayOf("sift.server.encryption-key=", "sift.server.auth.client-id=sift-web"),
            arrayOf("sift.server.encryption-key=c2VjcmV0"),
            arrayOf("sift.server.encryption-key=c2VjcmV0", "sift.server.auth.client-id= "),
            arrayOf(*REQUIRED, "sift.server.namespace=Not_A_Label"),
            arrayOf(*REQUIRED, "sift.server.auth.claims.username="),
        ).forEach { values ->
            runner.withPropertyValues(*values).run { context -> assertNotNull(context.startupFailure, values.joinToString()) }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ServerProperties::class)
    class PropertiesConfiguration

    companion object {
        private val REQUIRED = arrayOf("sift.server.encryption-key=c2VjcmV0", "sift.server.auth.client-id=sift-web")
    }
}
