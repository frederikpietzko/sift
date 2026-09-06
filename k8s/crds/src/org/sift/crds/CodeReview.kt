package org.sift.crds

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import io.fabric8.crd.generator.annotation.PrinterColumn
import io.fabric8.generator.annotation.Pattern
import io.fabric8.generator.annotation.Required
import io.fabric8.kubernetes.api.model.Condition
import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.Group
import io.fabric8.kubernetes.model.annotation.Kind
import io.fabric8.kubernetes.model.annotation.Plural
import io.fabric8.kubernetes.model.annotation.Version
import org.sift.crds.utils.NoArg

@Group("sift.org")
@Kind("CodeReview")
@Version("v1alpha1")
@Plural("codereviews")
@JsonClassDescription("CodeReview")
class CodeReview : CustomResource<CodeReview.Spec, CodeReview.Status>(), Namespaced {
    @NoArg
    @JsonClassDescription("CodeReviewSpec")
    data class Spec(
        @field:JsonPropertyDescription("The repository URL of the repository that needs to be reviewed.")
        @field:Pattern("^\\S+$")
        @field:Required val repositoryUrl: String,
        @field:JsonPropertyDescription("The branch of the repository that needs to be reviewed.")
        @field:Pattern("^\\S+$")
        @field:Required val branch: String,
        @field:JsonPropertyDescription("The base branch against which the requested commit is reviewed.")
        @field:Pattern("^\\S+$")
        @field:Required val baseBranch: String,
        @field:JsonPropertyDescription("The exact full 40-character hexadecimal Git commit SHA to review.")
        @field:Pattern("^[0-9a-fA-F]{40}$")
        @field:Required val commitSha: String,
        @field:JsonPropertyDescription("The pull request number of the repository that needs to be reviewed.")
        val pullRequest: String? = null,
    )

    @NoArg
    @JsonClassDescription("CodeReviewStatus")
    data class Status(
        @field:JsonPropertyDescription("The phase of the code review.")
        @field:PrinterColumn(name = "Phase", priority = 0)
        val phase: Phase? = null,
        @field:JsonPropertyDescription("Error messages or other relevant information.")
        @field:PrinterColumn(name = "Message", priority = 1)
        val message: String? = null,
        @field:JsonPropertyDescription("The spec generation represented by this status, not the latest event received.")
        val observedGeneration: Long? = null,
        @field:JsonPropertyDescription("Execution identity: <CodeReview UID>:<generation>.")
        val executionId: String? = null,
        @field:JsonPropertyDescription("The requested commit SHA for this execution.")
        val commitSha: String? = null,
        @field:JsonPropertyDescription("The owned Job for this execution, including UID to prevent name reuse.")
        val jobRef: ResourceReference? = null,
        @field:JsonPropertyDescription("The owned immutable ConfigMap for this execution.")
        val configMapRef: ResourceReference? = null,
        @field:JsonPropertyDescription("Execution start time in RFC 3339 UTC format.")
        val startedAt: String? = null,
        @field:JsonPropertyDescription("Execution completion time in RFC 3339 UTC format.")
        val completedAt: String? = null,
        @field:JsonPropertyDescription("Generation-aware conditions with reason, message and transition time.")
        val conditions: List<Condition> = emptyList(),
    )

    @NoArg
    @JsonClassDescription("ResourceReference")
    data class ResourceReference(
        @field:JsonPropertyDescription("The name of the referenced resource in the CodeReview's namespace.")
        @field:Required val name: String,
        @field:JsonPropertyDescription("The UID of the referenced resource, guarding against name reuse.")
        @field:Required val uid: String,
    )
}

enum class Phase {
    @JsonEnumDefaultValue
    CREATED,
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
}
