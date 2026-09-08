package org.sift.server.agents

import io.fabric8.kubernetes.api.model.DeleteOptions
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.kubernetes.client.utils.Serialization
import io.mockk.every
import io.mockk.mockk
import org.sift.crds.CodeReview
import org.sift.server.config.ServerProperties
import org.sift.server.repositories.Repository
import org.sift.server.repositories.RepositoryService
import org.sift.server.repositories.SecretRef
import tools.jackson.databind.json.JsonMapper
import java.net.HttpURLConnection
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Expectation mode: the recorded HTTP requests are asserted directly, as in `RepositorySecretSyncTest`. */
@EnableKubernetesMockClient
class CodeReviewAdapterTest {
    lateinit var client: KubernetesClient
    lateinit var server: KubernetesMockServer

    private val properties = ServerProperties(
        namespace = "sift-test",
        encryptionKey = "unused",
        auth = ServerProperties.Auth(clientId = "sift-web"),
    )
    private val repositories = mockk<RepositoryService>()
    private val adapter by lazy { CodeReviewAdapter(client, properties, repositories) }

    private val repositoryId: UUID = UUID.fromString("9b2c0c8e-1f4e-4c21-a9c8-4b1b5e1f8d10")
    private val runId: UUID = UUID.fromString("0f6c1d2e-3a4b-4c5d-8e9f-a0b1c2d3e4f5")
    private val collectionPath = "/apis/sift.org/v1alpha1/namespaces/sift-test/codereviews"
    private val request = CreateAgentRunRequest(
        kind = AgentKind.CODE_REVIEW,
        repositoryId = repositoryId,
        branch = "feature/x",
        baseBranch = "main",
        commitSha = "c".repeat(SHA_LENGTH),
        pullRequest = "7",
    )

    init {
        every { repositories.get(repositoryId) } returns Repository(
            id = repositoryId,
            name = "sift",
            url = "https://github.com/sift/sift.git",
            token = null,
            secretName = null,
            createdAt = OffsetDateTime.parse("2026-09-07T10:00:00Z"),
            updatedAt = OffsetDateTime.parse("2026-09-07T10:00:00Z"),
        )
    }

    @Test
    fun `resource name is the run id with the cr prefix`() {
        assertEquals("cr-$runId", adapter.resourceName(runId))
    }

    @Test
    fun `apply creates a labelled CodeReview with the repository url and secret ref`() {
        every { repositories.secretRef(repositoryId) } returns SecretRef(name = "sift-repo-$repositoryId")
        val created = CodeReview().apply {
            metadata = ObjectMetaBuilder().withName("cr-$runId").withUid("uid-123").build()
        }
        server.expect().post().withPath(collectionPath).andReturn(HttpURLConnection.HTTP_CREATED, created).once()

        val applied = adapter.apply(run(), request)

        assertEquals(AppliedResource(crName = "cr-$runId", crUid = "uid-123"), applied)
        val recorded = assertNotNull(server.lastRequest)
        assertEquals("POST", recorded.method)
        assertEquals(collectionPath, recorded.path)
        val codeReview = Serialization.unmarshal(recorded.body.readUtf8(), CodeReview::class.java)
        assertEquals("CodeReview", codeReview.kind)
        assertEquals("sift.org/v1alpha1", codeReview.apiVersion)
        assertEquals("cr-$runId", codeReview.metadata.name)
        assertEquals("sift-test", codeReview.metadata.namespace)
        assertEquals(runId.toString(), codeReview.metadata.labels[CodeReviewAdapter.RUN_LABEL])
        assertEquals(repositoryId.toString(), codeReview.metadata.labels[CodeReviewAdapter.REPOSITORY_LABEL])
        assertEquals("sift-server", codeReview.metadata.labels[CodeReviewAdapter.MANAGED_BY_LABEL])
        assertEquals("https://github.com/sift/sift.git", codeReview.spec.repositoryUrl)
        assertEquals("feature/x", codeReview.spec.branch)
        assertEquals("main", codeReview.spec.baseBranch)
        assertEquals("c".repeat(SHA_LENGTH), codeReview.spec.commitSha)
        assertEquals("7", codeReview.spec.pullRequest)
        assertEquals("sift-repo-$repositoryId", assertNotNull(codeReview.spec.credentialsSecretRef).name)
        assertEquals("token", assertNotNull(codeReview.spec.credentialsSecretRef).key)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `apply omits the secret ref for repositories without credentials`() {
        every { repositories.secretRef(repositoryId) } returns null
        server.expect().post().withPath(collectionPath).andReturn(HttpURLConnection.HTTP_CREATED, CodeReview()).once()

        val applied = adapter.apply(run(), request)

        assertEquals("cr-$runId", applied.crName)
        assertNull(applied.crUid)
        val body = assertNotNull(server.lastRequest).body.readUtf8()
        assertNull(Serialization.unmarshal(body, CodeReview::class.java).spec.credentialsSecretRef)
    }

    @Test
    fun `delete issues a foreground delete and ignores a missing CodeReview`() {
        val path = "$collectionPath/cr-$runId"
        server.expect().delete().withPath(path).andReturn(HttpURLConnection.HTTP_OK, CodeReview()).once()

        adapter.delete(run())

        val recorded = assertNotNull(server.lastRequest)
        assertEquals("DELETE", recorded.method)
        assertEquals(path, recorded.path)
        val options = Serialization.unmarshal(recorded.body.readUtf8(), DeleteOptions::class.java)
        assertEquals("Foreground", options.propagationPolicy)

        val notFound = StatusBuilder().withCode(HttpURLConnection.HTTP_NOT_FOUND).build()
        server.expect().delete().withPath(path).andReturn(HttpURLConnection.HTTP_NOT_FOUND, notFound).once()
        adapter.delete(run())
        assertEquals(2, server.requestCount)
    }

    private fun run(): AgentRun {
        val now = OffsetDateTime.parse("2026-09-07T10:00:00Z")
        return AgentRun(
            id = runId,
            kind = AgentKind.CODE_REVIEW,
            source = RunSource.API,
            repositoryId = repositoryId,
            crName = "cr-$runId",
            crUid = null,
            generation = null,
            executionId = null,
            phase = AgentPhase.CREATED,
            reason = null,
            message = null,
            spec = JsonMapper.builder().build().createObjectNode(),
            createdAt = now,
            startedAt = null,
            completedAt = null,
            observedAt = null,
            updatedAt = now,
        )
    }

    companion object {
        private const val SHA_LENGTH = 40
    }
}
