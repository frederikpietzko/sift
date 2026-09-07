package org.sift.server.agents

import io.fabric8.kubernetes.api.model.DeletionPropagation
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.sift.crds.CodeReview
import org.sift.server.config.ServerProperties
import org.sift.server.repositories.RepositoryService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Applies `CodeReview` custom resources for `CODE_REVIEW` runs. The CR lives in `sift.server.namespace`,
 * is named `cr-<run id>` and carries the run and repository ids as labels so the operator's events can be
 * correlated back. Credentials are referenced, never copied: `spec.credentialsSecretRef` points at the
 * Secret `RepositorySecretSync` maintains for the repository.
 */
@Component
class CodeReviewAdapter(
    private val client: KubernetesClient,
    private val properties: ServerProperties,
    private val repositories: RepositoryService,
) : AgentKindAdapter {
    override val kind: AgentKind = AgentKind.CODE_REVIEW

    override fun resourceName(runId: UUID): String = "$NAME_PREFIX$runId"

    override fun apply(run: AgentRun, request: CreateAgentRunRequest): AppliedResource {
        val repository = repositories.get(request.repositoryId)
        val secretRef = repositories.secretRef(request.repositoryId)
        val name = resourceName(run.id)
        val codeReview = CodeReview().apply {
            metadata = ObjectMetaBuilder()
                .withName(name)
                .withNamespace(properties.namespace)
                .addToLabels(MANAGED_BY_LABEL, FIELD_MANAGER)
                .addToLabels(RUN_LABEL, run.id.toString())
                .addToLabels(REPOSITORY_LABEL, request.repositoryId.toString())
                .build()
            spec = CodeReview.Spec(
                repositoryUrl = repository.url,
                branch = request.branch,
                baseBranch = request.baseBranch,
                commitSha = request.commitSha,
                pullRequest = request.pullRequest,
                credentialsSecretRef = secretRef?.let { CodeReview.SecretKeySelector(name = it.name, key = it.key) },
            )
        }
        val created = codeReviews().resource(codeReview).create()
        return AppliedResource(crName = name, crUid = created?.metadata?.uid)
    }

    override fun delete(run: AgentRun) {
        codeReviews().withName(run.crName).withPropagationPolicy(DeletionPropagation.FOREGROUND).delete()
    }

    private fun codeReviews() = client.resources(CodeReview::class.java).inNamespace(properties.namespace)

    companion object {
        const val NAME_PREFIX = "cr-"
        const val FIELD_MANAGER = "sift-server"
        const val MANAGED_BY_LABEL = "app.kubernetes.io/managed-by"
        const val RUN_LABEL = "sift.org/run-id"
        const val REPOSITORY_LABEL = "sift.org/repository-id"
    }
}
