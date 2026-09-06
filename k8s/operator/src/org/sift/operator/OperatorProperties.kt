package org.sift.operator

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties("sift.operator")
data class OperatorProperties(
    @field:NotBlank
    @field:Pattern(regexp = "[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")
    val namespace: String,
    @field:Valid val review: Review,
    @field:Valid val services: Services = Services(),
    @field:Valid val secrets: Secrets = Secrets(),
) {
    data class Review(
        @field:NotBlank val image: String,
        @field:NotBlank val serviceAccount: String = "sift-review",
        @field:Min(1) @field:Max(MAX_DEADLINE_SECONDS) val deadlineSeconds: Long = 3600,
        @field:Valid val resources: Resources = Resources(),
    )

    data class Resources(
        @field:NotBlank val cpuRequest: String = "500m",
        @field:NotBlank val cpuLimit: String = "2",
        @field:NotBlank val memoryRequest: String = "512Mi",
        @field:NotBlank val memoryLimit: String = "2Gi",
        @field:NotBlank val scratchSizeLimit: String = "2Gi",
    )

    data class Services(
        val modelBaseUrl: String? = null,
        val model: String? = null,
        val searxngUrl: String? = null,
        @field:NotBlank val rabbitmqHost: String = "rabbitmq",
        @field:Min(1) @field:Max(MAX_PORT) val rabbitmqPort: Int = 5672,
        @field:NotBlank val rabbitmqUsername: String = "sift",
        @field:NotBlank val rabbitmqVirtualHost: String = "/",
    )

    data class Secrets(
        @field:Valid val modelApiKey: SecretKeyReference? = null,
        @field:Valid val proxyToken: SecretKeyReference? = null,
        @field:Valid val rabbitmqPassword: SecretKeyReference? = null,
        @field:Valid val gitToken: SecretKeyReference? = null,
    )

    data class SecretKeyReference(
        @field:NotBlank val name: String,
        @field:NotBlank val key: String,
    )
}

private const val MAX_DEADLINE_SECONDS = 86400L
private const val MAX_PORT = 65535L
