package org.sift.e2e

import io.fabric8.kubernetes.client.KubernetesClient
import org.sift.crds.CodeReview
import java.sql.DriverManager

/**
 * Removes only what a scenario created: the CodeReview CR, the mirrored `sift-repo-<id>` Secret and
 * the `review_results`/`agent_runs`/`repositories` rows. Infrastructure (cluster, Compose,
 * credentials Secret) is deliberately kept for fast re-runs.
 *
 * The rows are purged over JDBC rather than `DELETE /api/v1/repositories/{id}` because `agent_runs`
 * references `repositories` without cascade, so the API delete fails once a terminal run exists.
 */
object TestData {
    const val REPOSITORY_SECRET_PREFIX = "sift-repo-"

    /** Idempotent: fabric8 `delete()` on a missing resource is not an error. */
    fun deleteCodeReview(client: KubernetesClient, name: String, namespace: String = ClusterResources.NAMESPACE) {
        client.resources(CodeReview::class.java).inNamespace(namespace).withName(name).delete()
    }

    fun deleteRepositorySecret(
        client: KubernetesClient,
        repositoryId: String,
        namespace: String = ClusterResources.NAMESPACE,
    ) {
        client.secrets().inNamespace(namespace).withName("$REPOSITORY_SECRET_PREFIX$repositoryId").delete()
    }

    /** Same three deletes as `ServerEndToEndTest.cleanUp`, keyed by the unique repository name. */
    fun purgeRows(
        repositoryName: String,
        jdbcUrl: String = Compose.POSTGRES_JDBC_URL,
        user: String = Compose.POSTGRES_USER,
        password: String = Compose.POSTGRES_PASSWORD,
    ): Int {
        DriverManager.getConnection(jdbcUrl, user, password).use { connection ->
            return purgeStatements.sumOf { sql ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, repositoryName)
                    statement.executeUpdate()
                }
            }
        }
    }

    private val purgeStatements = [
        """
        delete from review_results where agent_run_id in
            (select id from agent_runs where repository_id in (select id from repositories where name = ?))
        """.trimIndent(),
        "delete from agent_runs where repository_id in (select id from repositories where name = ?)",
        "delete from repositories where name = ?",
    ]
}
