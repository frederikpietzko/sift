package org.sift.server.repositories.secrets

import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.sift.server.config.ServerProperties
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Mirrors a repository's plaintext token into an Opaque Secret `<secretPrefix><id>` (key `token`) in the
 * server namespace. Fabric8 `delete()` is idempotent, so removing a missing Secret is not an error.
 */
@Component
class RepositorySecretSync(
    private val client: KubernetesClient,
    private val properties: ServerProperties,
) {
    fun secretName(id: UUID): String = "${properties.secretPrefix}$id"

    fun apply(id: UUID, token: String): String {
        val name = secretName(id)
        val secret = SecretBuilder()
            .withNewMetadata().withName(name).withNamespace(properties.namespace)
            .addToLabels(MANAGED_BY_LABEL, FIELD_MANAGER).addToLabels(REPOSITORY_LABEL, id.toString())
            .endMetadata()
            .withType("Opaque")
            .addToStringData(TOKEN_KEY, token)
            .build()
        client.secrets().inNamespace(properties.namespace).resource(secret)
            .fieldManager(FIELD_MANAGER).forceConflicts().serverSideApply()
        return name
    }

    fun delete(id: UUID) {
        client.secrets().inNamespace(properties.namespace).withName(secretName(id)).delete()
    }

    companion object {
        const val TOKEN_KEY = "token"
        const val FIELD_MANAGER = "sift-server"
        const val MANAGED_BY_LABEL = "app.kubernetes.io/managed-by"
        const val REPOSITORY_LABEL = "sift.org/repository-id"
    }
}
