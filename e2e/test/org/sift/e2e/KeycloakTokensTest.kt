package org.sift.e2e

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeycloakTokensTest {
    private val mapper = SseFrameReader.defaultMapper

    @Test
    fun `token endpoint is derived from the issuer regardless of a trailing slash`() {
        val expected = URI.create("http://localhost:8180/realms/sift/protocol/openid-connect/token")

        assertEquals(expected, KeycloakTokens.tokenEndpoint("http://localhost:8180/realms/sift"))
        assertEquals(expected, KeycloakTokens.tokenEndpoint("http://localhost:8180/realms/sift/"))
    }

    @Test
    fun `password grant body is form encoded`() {
        val body = KeycloakTokens.passwordGrantBody(clientId = "sift-web", username = "e2e", password = "p&ss w=rd")

        assertEquals(
            "grant_type=password&client_id=sift-web&username=e2e&password=p%26ss+w%3Drd&scope=openid+profile+email",
            body,
        )
    }

    @Test
    fun `token response yields the access token and its expiry`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val json = mapper.readTree("""{"access_token":"abc.def.ghi","expires_in":300,"token_type":"Bearer"}""")

        val token = KeycloakTokens.parseTokenResponse(json, now)

        assertEquals("abc.def.ghi", token.value)
        assertEquals(now.plusSeconds(300), token.expiresAt)
        assertTrue(token.isFresh(now))
        assertTrue(token.isFresh(now.plusSeconds(269)))
        assertFalse(token.isFresh(now.plusSeconds(271)), "must be refreshed within the safety margin")
        assertFalse(token.isFresh(now, margin = Duration.ofMinutes(10)))
    }

    @Test
    fun `token response without access token or expiry is rejected`() {
        assertThrows<IllegalStateException> { KeycloakTokens.parseTokenResponse(mapper.readTree("""{"expires_in":5}""")) }
        assertThrows<IllegalStateException> { KeycloakTokens.parseTokenResponse(mapper.readTree("""{"access_token":"x"}""")) }
    }
}
