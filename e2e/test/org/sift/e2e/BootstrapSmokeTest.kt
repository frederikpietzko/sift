package org.sift.e2e

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the real bootstrap helpers against the host's Docker and kind. Skipped unless
 * `SIFT_E2E=true`; the cluster and containers are kept afterwards for fast re-runs
 * (set `SIFT_E2E_DESTROY=true` to delete the `sift-e2e` cluster at the end).
 */
@EnabledIfEnvironmentVariable(named = "SIFT_E2E", matches = "true")
class BootstrapSmokeTest {

    @Test
    fun `compose infra and the dedicated kind cluster can be ensured idempotently`() {
        Preflight.requireTools()
        Compose.up()

        val cluster = KindCluster()
        try {
            cluster.ensure()
            assertTrue(cluster.exists())
            assertTrue(cluster.kubeconfig.isFile, "kubeconfig should be written to ${cluster.kubeconfig}")
            assertEquals(false, cluster.ensure(), "second ensure() must reuse the existing cluster")

            cluster.client().use { client ->
                val nodes = client.nodes().list().items
                assertTrue(nodes.isNotEmpty(), "cluster should report at least one node")
                ClusterResources.describeNodes(client)

                ClusterResources.install(client)
                ClusterResources.install(client) // second apply must be a no-op
                val credentials = System.getenv().let { env ->
                    Credentials(
                        openAiApiKey = env[Preflight.OPENAI_API_KEY] ?: "e2e-smoke-placeholder",
                        modelProxyToken = env[Preflight.MODEL_PROXY_TOKEN] ?: "e2e-smoke-placeholder",
                    )
                }
                ClusterResources.provisionCredentials(client, credentials)

                val secret = client.secrets().inNamespace(ClusterResources.NAMESPACE)
                    .withName(ClusterResources.CREDENTIALS_SECRET).get()
                assertEquals(
                    setOf("model-api-key", "proxy-token", "rabbitmq-password"),
                    secret?.data?.keys?.toSet(),
                    "credentials Secret should exist with the expected keys",
                )
            }
        } finally {
            if (System.getenv("SIFT_E2E_DESTROY") == "true") cluster.destroy()
        }
    }
}
