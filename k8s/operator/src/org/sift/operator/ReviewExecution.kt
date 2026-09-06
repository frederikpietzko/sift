package org.sift.operator

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import org.sift.crds.CodeReview
import java.net.URI
import java.security.MessageDigest

class ReviewExecution(private val review: CodeReview) {
    val uid: String = requireNotNull(review.metadata.uid)
    val generation: Long = requireNotNull(review.metadata.generation).also { require(it > 0) }
    val namespace: String = requireNotNull(review.metadata.namespace)
    val executionId: String = "$uid:$generation"
    private val suffix = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray())
        .take(UID_HASH_BYTES).joinToString("") { "%02x".format(it) }
    private val prefix = review.metadata.name.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        .take(NAME_PREFIX_LENGTH).trim('-').ifEmpty { "review" }
    val name: String = "$prefix-$suffix-g$generation"
    val labels: Map<String, String> = mapOf(OWNER_LABEL to uid, GENERATION_LABEL to generation.toString())

    fun metadata(): ObjectMeta = ObjectMetaBuilder()
        .withName(name).withNamespace(namespace).withLabels<String, String>(labels)
        .addNewOwnerReference()
        .withApiVersion(review.apiVersion).withKind(review.kind)
        .withName(review.metadata.name).withUid(uid).withController(true)
        .withBlockOwnerDeletion(true).endOwnerReference().build()

    fun requireOwned(resource: HasMetadata) {
        require(resource.metadata.namespace == namespace && resource.metadata.name == name) {
            "Execution resource identity conflict"
        }
        require(owns(resource) && labels.all { (key, value) -> resource.metadata.labels?.get(key) == value }) {
            "Execution resource ownership conflict"
        }
    }

    fun owns(resource: HasMetadata): Boolean = resource.metadata.ownerReferences.any {
        it.uid == uid && it.controller == true && it.kind == review.kind && it.apiVersion == review.apiVersion
    }

    companion object {
        const val OWNER_LABEL = "sift.org/review-uid"
        const val GENERATION_LABEL = "sift.org/generation"

        fun validate(review: CodeReview) {
            val spec = requireNotNull(review.spec) { "Missing review spec" }
            val strings = listOfNotNull(
                spec.repositoryUrl, spec.branch, spec.baseBranch, spec.commitSha, spec.pullRequest,
            )
            require(strings.all { it.isNotBlank() && !it.contains(Regex("\\s")) && !it.contains("\${") }) {
                "Review fields must be nonblank literals without whitespace or Spring placeholders"
            }
            require(spec.commitSha.matches(Regex("[0-9a-fA-F]{40}"))) { "A full commit SHA is required" }
            val uri = URI.create(spec.repositoryUrl)
            require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Repository must be an HTTP(S) URL without embedded credentials, query or fragment"
            }
        }
    }
}

private const val UID_HASH_BYTES = 8
private const val NAME_PREFIX_LENGTH = 20
