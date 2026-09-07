package org.sift.e2e

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostProcessEnvTest {

    private val hostEnv = mapOf(
        "PATH" to "/usr/bin",
        "HOME" to "/Users/dev",
        "JAVA_HOME" to "/jdk",
        "OPENAI_API_KEY" to "sk-secret",
        "SIFT_MODEL_PROXY_TOKEN" to "token",
        "SIFT_REVIEW_AUTH_TOKEN" to "ghp",
        "SPRING_PROFILES_ACTIVE" to "local",
        "SPRING_APPLICATION_JSON" to "{}",
        "KUBERNETES_SERVICE_HOST" to "10.0.0.1",
        "KUBECONFIG" to "/Users/dev/.kube/config",
        "SIFT_SERVER_ENCRYPTION_KEY" to "stale",
        "SIFT_OPERATOR_NAMESPACE" to "other",
        "SERVER_PORT" to "9999",
        "SIFT_REVIEW_IMAGE" to "example/image@sha256:abc",
    )

    @Test
    fun `scrubbing drops credentials and Spring or Kubernetes overrides but keeps the rest`() {
        val env = HostProcess.scrubbedEnvironment(hostEnv, emptyMap())

        assertEquals(setOf("PATH", "HOME", "JAVA_HOME", "SIFT_REVIEW_IMAGE"), env.keys)
        assertFalse(env.values.any { it == "sk-secret" || it == "token" || it == "ghp" }, "no credential value may leak")
    }

    @Test
    fun `overrides win over host values and are the only route for controlled variables`() {
        val env = HostProcess.scrubbedEnvironment(
            hostEnv,
            mapOf("KUBECONFIG" to "/repo/build/e2e/kubeconfig", "SERVER_PORT" to "18080", "PATH" to "/opt/bin"),
        )

        assertEquals("/repo/build/e2e/kubeconfig", env["KUBECONFIG"])
        assertEquals("18080", env["SERVER_PORT"])
        assertEquals("/opt/bin", env["PATH"])
        assertTrue(env.keys.none { it.startsWith("SPRING_") || it.startsWith("KUBERNETES_") })
    }

    @Test
    fun `encryption key is 32 random base64 encoded bytes`() {
        val key = SiftEnvironment.randomEncryptionKey()

        assertEquals(32, java.util.Base64.getDecoder().decode(key).size)
        assertTrue(key != SiftEnvironment.randomEncryptionKey(), "keys must be random per boot")
    }
}
