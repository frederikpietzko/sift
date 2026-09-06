package org.sift.operator

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import org.sift.crds.CodeReview
import org.springframework.stereotype.Component

@Component
class ReviewResources(
    private val properties: OperatorProperties,
    private val configuration: ReviewConfiguration,
) {
    fun configMap(review: CodeReview): ConfigMap = ConfigMapBuilder()
        .withMetadata(execution(review).metadata()).withImmutable(true)
        .addToData("application.yaml", configuration.yaml(review)).build()

    fun job(review: CodeReview): Job {
        val execution = execution(review)
        val settings = properties.review
        val resources = settings.resources
        return JobBuilder().withMetadata(execution.metadata())
            .withNewSpec().withBackoffLimit(0).withActiveDeadlineSeconds(settings.deadlineSeconds)
            .withCompletions(1).withParallelism(1)
            .withNewTemplate()
            .withNewMetadata().withLabels<String, String>(execution.labels).endMetadata()
            .withNewSpec().withRestartPolicy("Never")
            .withServiceAccountName(settings.serviceAccount).withAutomountServiceAccountToken(false)
            .withNewSecurityContext().withRunAsNonRoot(true)
            .withRunAsUser(REVIEW_USER_ID).withRunAsGroup(REVIEW_USER_ID)
            .withFsGroup(REVIEW_USER_ID).withNewSeccompProfile().withType("RuntimeDefault").endSeccompProfile()
            .endSecurityContext()
            .addNewContainer().withName("review").withImage(settings.image).withImagePullPolicy("IfNotPresent")
            .withWorkingDir("/scratch").withEnv(environment())
            .withNewSecurityContext().withAllowPrivilegeEscalation(false).withReadOnlyRootFilesystem(true)
            .withNewCapabilities().withDrop("ALL").endCapabilities().endSecurityContext()
            .withNewResources()
            .addToRequests("cpu", Quantity(resources.cpuRequest))
            .addToRequests("memory", Quantity(resources.memoryRequest))
            .addToLimits("cpu", Quantity(resources.cpuLimit)).addToLimits("memory", Quantity(resources.memoryLimit))
            .endResources()
            .addNewVolumeMount().withName("configuration").withMountPath("/etc/sift/review")
            .withReadOnly(true).endVolumeMount()
            .addNewVolumeMount().withName("scratch").withMountPath("/scratch").endVolumeMount()
            .addNewVolumeMount().withName("scratch").withMountPath("/tmp").endVolumeMount()
            .endContainer()
            .addNewVolume().withName("configuration").withNewConfigMap().withName(execution.name)
            .withDefaultMode(CONFIG_READ_ONLY_MODE).endConfigMap().endVolume()
            .addNewVolume().withName("scratch").withNewEmptyDir().withSizeLimit(Quantity(resources.scratchSizeLimit))
            .endEmptyDir().endVolume()
            .endSpec().endTemplate().endSpec().build()
    }

    private fun execution(review: CodeReview): ReviewExecution {
        ReviewExecution.validate(review)
        require(review.metadata.namespace == properties.namespace) { "Review is outside the configured namespace" }
        require(properties.review.image.isNotBlank()) { "Review image must not be blank" }
        validateQuantities()
        return ReviewExecution(review)
    }

    private fun validateQuantities() {
        val resources = properties.review.resources
        listOf(resources.cpuRequest to resources.cpuLimit, resources.memoryRequest to resources.memoryLimit)
            .forEach { [request, limit] ->
                val minimum = Quantity.getAmountInBytes(Quantity(request))
                val maximum = Quantity.getAmountInBytes(Quantity(limit))
                require(minimum.signum() > 0 && maximum >= minimum) { "Invalid review resource budget" }
            }
        require(Quantity.getAmountInBytes(Quantity(resources.scratchSizeLimit)).signum() > 0) {
            "Invalid scratch resource budget"
        }
    }

    private fun environment(): List<EnvVar> {
        val secrets = properties.secrets
        val plain = mapOf(
            "SPRING_CONFIG_ADDITIONAL_LOCATION" to "file:/etc/sift/review/application.yaml",
            "HOME" to "/scratch",
            "TMPDIR" to "/tmp",
            "JAVA_TOOL_OPTIONS" to "-Djava.io.tmpdir=/tmp -Duser.home=/scratch",
        ).map { [name, value] -> EnvVarBuilder().withName(name).withValue(value).build() }
        return plain + listOfNotNull(
            secret("OPENAI_API_KEY", secrets.modelApiKey),
            secret("SIFT_MODEL_PROXY_TOKEN", secrets.proxyToken),
            secret("SPRING_RABBITMQ_PASSWORD", secrets.rabbitmqPassword),
            secret("SIFT_REVIEW_AUTH_TOKEN", secrets.gitToken),
        )
    }

    private fun secret(name: String, reference: OperatorProperties.SecretKeyReference?): EnvVar? = reference?.let {
        EnvVarBuilder().withName(name).withNewValueFrom().withNewSecretKeyRef()
            .withName(it.name).withKey(it.key).withOptional(false).endSecretKeyRef().endValueFrom().build()
    }
}

private const val REVIEW_USER_ID = 10001L
private const val CONFIG_READ_ONLY_MODE = 292 // 0444
