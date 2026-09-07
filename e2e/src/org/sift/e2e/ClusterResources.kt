package org.sift.e2e

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Namespace
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionCondition
import io.fabric8.kubernetes.api.model.apps.DeploymentCondition
import io.fabric8.kubernetes.client.KubernetesClient
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Installs everything the `sift-e2e` cluster needs before the operator and server start, mirroring
 * the manual workflow in `docs/system-components/local-kind-development.md`: the CodeReview CRD,
 * the `sift-dev` namespace with the review ServiceAccount and HAProxy bridges, and the operator RBAC.
 *
 * Every manifest is server-side applied with field manager [FIELD_MANAGER], so repeated runs are
 * idempotent and never fight with objects created by other tooling.
 */
object ClusterResources {
    const val FIELD_MANAGER = "sift-e2e"
    const val NAMESPACE = "sift-dev"
    const val CREDENTIALS_SECRET = "sift-local-credentials"
    const val BRIDGES_DEPLOYMENT = "sift-local-bridges"
    const val CODE_REVIEW_CRD = "codereviews.sift.org"

    private val manifestDirs = ["k8s/manifests/crds", "k8s/manifests/local", "k8s/manifests/operator"]
    private val crdReadyTimeout = 2.minutes
    private val bridgesReadyTimeout = 5.minutes

    /** Applies all manifests and blocks until the CRD is established and the bridges are available. */
    fun install(client: KubernetesClient) {
        val manifests = manifestFiles()
        val applied = manifests
            .flatMap { file -> file.inputStream().use { stream -> client.load(stream).items() } }
            .sortedBy(::applyOrder)
        applied.forEach { client.resource(it).fieldManager(FIELD_MANAGER).forceConflicts().serverSideApply() }
        println("Applied ${applied.size} resources from ${manifests.size} manifests with field manager $FIELD_MANAGER")

        awaitCrdEstablished(client, crdReadyTimeout)
        awaitDeploymentAvailable(client, BRIDGES_DEPLOYMENT, bridgesReadyTimeout)
    }

    /**
     * Creates or replaces the `sift-local-credentials` Secret the review Job reads. Values travel
     * only in the API request body; nothing about them is printed.
     */
    fun provisionCredentials(
        client: KubernetesClient,
        credentials: Credentials,
        rabbitMqPassword: String = Compose.RABBITMQ_PASSWORD,
    ) {
        val secret = credentialsSecret(credentials, rabbitMqPassword)
        client.resource(secret).fieldManager(FIELD_MANAGER).forceConflicts().serverSideApply()
        println("Provisioned Secret $NAMESPACE/$CREDENTIALS_SECRET (keys: ${secret.data.keys.sorted().joinToString()})")
    }

    /** Builds the credentials Secret without touching the cluster; exposed for unit tests. */
    fun credentialsSecret(credentials: Credentials, rabbitMqPassword: String): Secret =
        SecretBuilder()
            .withNewMetadata()
            .withName(CREDENTIALS_SECRET)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/managed-by", FIELD_MANAGER)
            .endMetadata()
            .withType("Opaque")
            .addToData("model-api-key", encode(credentials.openAiApiKey))
            .addToData("proxy-token", encode(credentials.modelProxyToken))
            .addToData("rabbitmq-password", encode(rabbitMqPassword))
            .build()

    /** Informational: the published review image is architecture specific, so print what the nodes run. */
    fun describeNodes(client: KubernetesClient): String {
        val nodes = client.nodes().list().items
        val architectures = nodes.map { it.status.nodeInfo.architecture }.toSortedSet()
        val summary =
            "nodes: ${nodes.joinToString { it.metadata.name }}; architectures: ${architectures.joinToString()}"
        println(summary)
        return summary
    }

    fun awaitCrdEstablished(client: KubernetesClient, timeout: Duration) {
        client.apiextensions().v1().customResourceDefinitions().withName(CODE_REVIEW_CRD)
            .waitUntilCondition(
                { crd -> crd?.status?.conditions.hasTrue("Established") },
                timeout.inWholeSeconds,
                TimeUnit.SECONDS,
            )
            ?: throw E2ePreconditionException("CRD $CODE_REVIEW_CRD was not established within $timeout")
    }

    fun awaitDeploymentAvailable(client: KubernetesClient, name: String, timeout: Duration) {
        client.apps().deployments().inNamespace(NAMESPACE).withName(name)
            .waitUntilCondition(
                { deployment -> deployment?.status?.conditions.hasTrue("Available") },
                timeout.inWholeSeconds,
                TimeUnit.SECONDS,
            )
            ?: throw E2ePreconditionException(
                "Deployment $NAMESPACE/$name was not Available within $timeout; " +
                    "check that Docker Desktop resolves host.docker.internal and the node can pull haproxy",
            )
    }

    fun manifestFiles(root: File = RepoRoot.dir): List<File> =
        manifestDirs.flatMap { dir ->
            root.resolve(dir).listFiles { file -> file.extension in ["yaml", "yml"] }
                ?.sortedBy { it.name }
                ?: throw E2ePreconditionException("Manifest directory $dir is missing under $root")
        }

    /** Cluster-scoped prerequisites first so namespaced objects never race their namespace. */
    private fun applyOrder(resource: HasMetadata): Int =
        when (resource) {
            is CustomResourceDefinition -> 0
            is Namespace -> 1
            else -> 2
        }

    /** CRD and Deployment conditions share the `type`/`status` shape but are unrelated fabric8 types. */
    private fun List<Any>?.hasTrue(type: String): Boolean =
        orEmpty().any {
            when (it) {
                is CustomResourceDefinitionCondition -> it.type == type && it.status == "True"
                is DeploymentCondition -> it.type == type && it.status == "True"
                else -> false
            }
        }

    private fun encode(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray())
}
