package org.sift.crds

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import io.fabric8.crd.generator.annotation.PrinterColumn
import io.fabric8.generator.annotation.Required
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
        @field:Required val repositoryUrl: String,
        @field:JsonPropertyDescription("The branch of the repository that needs to be reviewed.")
        @field:Required val branch: String,
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
