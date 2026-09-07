package org.sift.server.config

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("sift.server")
data class ServerProperties(
    @field:NotBlank
    @field:Pattern(regexp = "[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")
    val namespace: String = "sift-dev",
    /** Base64 encoded symmetric key used to encrypt repository tokens at rest. */
    @field:NotBlank val encryptionKey: String,
    @field:NotBlank val secretPrefix: String = "sift-repo-",
    @field:Valid val watch: Watch = Watch(),
) {
    data class Watch(
        /** `false` disables the Postgres `LISTEN` coroutine; the SSE endpoint then only serves snapshots. */
        val enabled: Boolean = true,
        val heartbeat: Duration = Duration.ofSeconds(DEFAULT_HEARTBEAT_SECONDS),
    )
}

private const val DEFAULT_HEARTBEAT_SECONDS = 15L
