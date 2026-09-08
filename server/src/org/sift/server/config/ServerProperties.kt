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
    @field:Valid val auth: Auth,
) {
    data class Watch(
        /** `false` disables the Postgres `LISTEN` coroutine; the SSE endpoint then only serves snapshots. */
        val enabled: Boolean = true,
        val heartbeat: Duration = Duration.ofSeconds(DEFAULT_HEARTBEAT_SECONDS),
    )

    /**
     * What the web client needs to run the authorization-code + PKCE flow against the OAuth2 provider. Token
     * validation itself is configured through the standard `spring.security.oauth2.resourceserver.jwt.*` keys;
     * the server never holds a client secret.
     */
    data class Auth(
        /** Public client id the SPA authenticates as (exposed through `GET /api/v1/auth/config`). */
        @field:NotBlank val clientId: String,
        val scopes: List<String> = listOf("openid", "profile", "email"),
        @field:Valid val claims: Claims = Claims(),
    ) {
        /** JWT claim names the user's display data is read from (differ between identity providers). */
        data class Claims(
            @field:NotBlank val username: String = "preferred_username",
            @field:NotBlank val email: String = "email",
        )
    }
}

private const val DEFAULT_HEARTBEAT_SECONDS = 15L
