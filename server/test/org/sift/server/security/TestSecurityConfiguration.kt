package org.sift.server.security

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidationException
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.Base64

/**
 * Replaces Boot's issuer-backed decoder so tests never talk to an identity provider: a token is the base64url
 * encoded JSON claim set produced by [TestTokens]; anything else is rejected the way a bad signature would be.
 * The real `BearerTokenAuthenticationFilter`, `SecurityFilterChain` and error handlers stay in place.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestSecurityConfiguration {
    @Bean
    @Primary
    fun testJwtDecoder(): JwtDecoder = JwtDecoder { token -> TestTokens.decode(token) }
}

object TestTokens {
    const val ISSUER = "https://issuer.test/realms/sift"
    const val AUDIENCE = "sift-server"
    const val SUBJECT = "0a1b2c3d-0000-4000-8000-000000000001"
    const val USERNAME = "alice"
    const val EMAIL = "alice@example.org"

    /** A structurally valid token that the decoder refuses (stands in for expired/forged/wrong-issuer tokens). */
    const val REJECTED = "rejected.token.value"

    private val mapper = JsonMapper.builder().build()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun bearer(
        subject: String = SUBJECT,
        username: String? = USERNAME,
        email: String? = EMAIL,
        issuer: String = ISSUER,
        extraClaims: Map<String, Any> = emptyMap(),
    ): String {
        val claims = buildMap<String, Any> {
            put("iss", issuer)
            put("sub", subject)
            put("aud", listOf(AUDIENCE))
            username?.let { put("preferred_username", it) }
            email?.let { put("email", it) }
            putAll(extraClaims)
        }
        return encoder.encodeToString(mapper.writeValueAsBytes(claims))
    }

    /** `Authorization` header value for HTTP clients that bypass MockMvc. */
    fun authorization(token: String = bearer()): String = "Bearer $token"

    /** MockMvc post-processor that authenticates the request as [SUBJECT] without going through the decoder. */
    fun authenticated(
        subject: String = SUBJECT,
        username: String? = USERNAME,
        email: String? = EMAIL,
    ): JwtRequestPostProcessor = jwt().jwt { jwt ->
        jwt.issuer(ISSUER).subject(subject).audience(listOf(AUDIENCE))
        username?.let { jwt.claim("preferred_username", it) }
        email?.let { jwt.claim("email", it) }
    }

    internal fun decode(token: String): Jwt {
        if (token == REJECTED) {
            throw JwtValidationException(
                "The token was rejected",
                listOf(OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The token was rejected", null)),
            )
        }
        val claims = try {
            mapper.readValue(decoder.decode(token), Map::class.java)
        } catch (exception: IllegalArgumentException) {
            throw BadJwtException("Malformed test token", exception)
        } catch (exception: JacksonException) {
            throw BadJwtException("Malformed test token", exception)
        }
        val now = Instant.now()
        val jwt = Jwt.withTokenValue(token)
            .header("alg", "none")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(TOKEN_LIFETIME_SECONDS))
        claims.forEach { (key, value) -> value?.let { jwt.claim(key.toString(), it) } }
        return jwt.build()
    }

    private const val TOKEN_LIFETIME_SECONDS = 300L
}
