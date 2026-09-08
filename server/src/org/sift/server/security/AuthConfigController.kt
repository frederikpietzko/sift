package org.sift.server.security

import org.sift.server.config.ServerProperties
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Anonymous discovery endpoint: the web client reads the provider and public client id from here instead of
 * carrying build-time configuration. Only what a public PKCE client needs is exposed; never a secret.
 */
@RestController
class AuthConfigController(
    private val properties: ServerProperties,
    private val resourceServer: OAuth2ResourceServerProperties,
) {
    @GetMapping(PATH)
    fun config(): AuthConfigResponse = AuthConfigResponse(
        issuerUri = resourceServer.jwt.issuerUri,
        clientId = properties.auth.clientId,
        scopes = properties.auth.scopes,
    )

    companion object {
        const val PATH = "/api/v1/auth/config"
    }
}

data class AuthConfigResponse(
    val issuerUri: String?,
    val clientId: String,
    val scopes: List<String>,
)
