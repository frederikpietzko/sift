package org.sift.e2e

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** A bearer token plus the moment Keycloak says it expires. */
data class AccessToken(val value: String, val expiresAt: Instant) {
    /** `true` while the token is good for at least [margin] more. */
    fun isFresh(now: Instant = Instant.now(), margin: Duration = REFRESH_MARGIN): Boolean =
        expiresAt.isAfter(now.plus(margin))

    companion object {
        /** Tokens are refreshed this long before they expire so in-flight requests never race the expiry. */
        val REFRESH_MARGIN: Duration = Duration.ofSeconds(30)
    }
}

/**
 * Obtains user access tokens from the Compose Keycloak with the OAuth2 *password* grant (direct access
 * grants are enabled on the public `sift-web` client of the dev realm only). The real web client uses
 * authorization code + PKCE; the password grant merely spares the harness a browser.
 */
object KeycloakTokens {
    private const val HTTP_OK = 200
    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val REQUEST_TIMEOUT_SECONDS = 30L
    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)
    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS)
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
    private val mapper: JsonMapper = SseFrameReader.defaultMapper

    fun passwordGrant(issuer: String, clientId: String, username: String, password: String): AccessToken {
        val request = HttpRequest.newBuilder(tokenEndpoint(issuer))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(passwordGrantBody(clientId, username, password)))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == HTTP_OK) {
            "Token request to ${request.uri()} for user '$username' failed with HTTP ${response.statusCode()}: " +
                response.body()
        }
        return parseTokenResponse(mapper.readTree(response.body()))
    }

    /** `{issuer}/protocol/openid-connect/token`, tolerant of a trailing slash on the issuer. */
    fun tokenEndpoint(issuer: String): URI = URI.create(issuer.trimEnd('/') + "/protocol/openid-connect/token")

    /** Form-encoded password grant asking for the scopes the server exposes via `/api/v1/auth/config`. */
    fun passwordGrantBody(clientId: String, username: String, password: String): String =
        listOf(
            "grant_type" to "password",
            "client_id" to clientId,
            "username" to username,
            "password" to password,
            "scope" to "openid profile email",
        ).joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, Charsets.UTF_8)}" }

    /** Reads `access_token` and `expires_in` from a token endpoint response. */
    fun parseTokenResponse(json: JsonNode, now: Instant = Instant.now()): AccessToken {
        val token = json.path("access_token").takeIf { it.isString }?.asString()
            ?: error("Token response has no access_token: $json")
        val expiresIn = json.path("expires_in").takeIf { it.isNumber }?.asLong()
            ?: error("Token response has no expires_in: $json")
        return AccessToken(value = token, expiresAt = now.plusSeconds(expiresIn))
    }
}
