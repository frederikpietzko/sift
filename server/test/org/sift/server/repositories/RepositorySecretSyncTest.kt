package org.sift.server.repositories

import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.kubernetes.client.utils.Serialization
import org.sift.server.config.ServerProperties
import java.net.HttpURLConnection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The fabric8 CRUD mock does not implement server-side apply, so expectations are used instead and the
 * recorded HTTP requests are asserted directly.
 */
@EnableKubernetesMockClient
class RepositorySecretSyncTest {
    lateinit var client: KubernetesClient
    lateinit var server: KubernetesMockServer

    private val properties = ServerProperties(namespace = "sift-test", encryptionKey = "unused")
    private val sync by lazy { RepositorySecretSync(client, properties) }
    private val id: UUID = UUID.fromString("9b2c0c8e-1f4e-4c21-a9c8-4b1b5e1f8d10")
    private val path = "/api/v1/namespaces/sift-test/secrets/sift-repo-$id"

    @Test
    fun `secret name is prefix plus repository id`() {
        assertEquals("sift-repo-$id", sync.secretName(id))
        val custom = RepositorySecretSync(client, properties.copy(secretPrefix = "repo-"))
        assertEquals("repo-$id", custom.secretName(id))
    }

    @Test
    fun `apply server side applies a labelled opaque secret holding the token`() {
        server.expect().patch().withPath("$path?fieldManager=sift-server&force=true")
            .andReturn(HttpURLConnection.HTTP_OK, Secret()).once()

        assertEquals("sift-repo-$id", sync.apply(id, "ghp_token"))

        val request = assertNotNull(server.lastRequest)
        assertEquals("PATCH", request.method)
        assertEquals("$path?fieldManager=sift-server&force=true", request.path)
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/apply-patch+yaml"))
        val secret = Serialization.unmarshal(request.body.readUtf8(), Secret::class.java)
        assertEquals("Secret", secret.kind)
        assertEquals("sift-repo-$id", secret.metadata.name)
        assertEquals("sift-test", secret.metadata.namespace)
        assertEquals("Opaque", secret.type)
        assertEquals("sift-server", secret.metadata.labels[RepositorySecretSync.MANAGED_BY_LABEL])
        assertEquals(id.toString(), secret.metadata.labels[RepositorySecretSync.REPOSITORY_LABEL])
        assertEquals(mapOf("token" to "ghp_token"), secret.stringData)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `delete issues a namespaced delete and ignores a missing secret`() {
        server.expect().delete().withPath(path).andReturn(HttpURLConnection.HTTP_OK, Secret()).once()
        sync.delete(id)
        assertEquals("DELETE", assertNotNull(server.lastRequest).method)
        assertEquals(path, assertNotNull(server.lastRequest).path)

        val notFound = StatusBuilder().withCode(HttpURLConnection.HTTP_NOT_FOUND).build()
        server.expect().delete().withPath(path).andReturn(HttpURLConnection.HTTP_NOT_FOUND, notFound).once()
        sync.delete(id)
        assertEquals(2, server.requestCount)
    }
}
