package org.sift.e2e

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The server acts as an OAuth2 resource server against the real Compose Keycloak: anonymous API calls
 * are rejected with an RFC 7807 problem, the SPA discovery endpoint stays public and a token obtained
 * with the password grant identifies the `e2e` user.
 */
@EnabledIfEnvironmentVariable(named = "SIFT_E2E", matches = "true")
@ExtendWith(SiftEnvironment::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthenticationTest(private val env: SiftEnvironment) {

    @Test
    fun `API requests without a bearer token are rejected with a problem detail`() {
        val response = env.anonymousApi().get("/api/v1/agents")

        assertEquals(ServerApi.HTTP_UNAUTHORIZED, response.status, response.rawBody)
        assertEquals(ServerApi.HTTP_UNAUTHORIZED.toLong(), response.json().path("status").asLong(), response.rawBody)
        assertTrue(response.json().path("title").isString, "problem detail must carry a title: ${response.rawBody}")
    }

    @Test
    fun `auth config is anonymous and points the SPA at the Keycloak realm without secrets`() {
        val config = env.anonymousApi().getJson("/api/v1/auth/config")

        assertEquals(env.issuerUri, config.path("issuerUri").asString())
        assertEquals(Compose.KEYCLOAK_CLIENT_ID, config.path("clientId").asString())
        assertTrue(config.path("scopes").isArray && config.path("scopes").size() > 0, config.toString())
        assertEquals(setOf("issuerUri", "clientId", "scopes"), config.propertyNames().toSet())
    }

    @Test
    fun `a real Keycloak token identifies the e2e user`() {
        val me = env.api().getJson("/api/v1/me")

        assertEquals(Compose.E2E_USER, me.path("username").asString())
        assertEquals(env.issuerUri, me.path("issuer").asString())
        assertFalse(me.path("subject").asString().isBlank(), "subject must be set")
        assertEquals("e2e@sift.local", me.path("email").asString())
    }
}
