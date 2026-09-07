package org.sift.e2e

import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClusterResourcesTest {

    @Test
    fun `credentials secret carries the three keys the review job expects`() {
        val secret = ClusterResources.credentialsSecret(
            credentials = Credentials(openAiApiKey = "sk-test", modelProxyToken = "proxy-test"),
            rabbitMqPassword = "sift",
        )

        assertEquals(ClusterResources.CREDENTIALS_SECRET, secret.metadata.name)
        assertEquals(ClusterResources.NAMESPACE, secret.metadata.namespace)
        assertEquals("Opaque", secret.type)
        assertEquals(setOf("model-api-key", "proxy-token", "rabbitmq-password"), secret.data.keys)
        assertEquals("sk-test", secret.data.getValue("model-api-key").decode())
        assertEquals("proxy-test", secret.data.getValue("proxy-token").decode())
        assertEquals("sift", secret.data.getValue("rabbitmq-password").decode())
        assertFalse(secret.toString().contains("sk-test"), "plain credential must not leak via toString")
    }

    @Test
    fun `manifest files cover crd, local and operator directories`() {
        val files = ClusterResources.manifestFiles()
        val names = files.map { it.name }

        assertTrue("codereviews.sift.org-v1.yml" in names)
        assertTrue("namespace.yaml" in names)
        assertTrue("bridges.yaml" in names)
        assertTrue("role.yaml" in names)
        assertTrue(files.all { it.isFile })
    }

    private fun String.decode(): String = String(Base64.getDecoder().decode(this))
}
