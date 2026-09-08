package org.sift.server.users

import io.mockk.every
import org.sift.server.security.TestTokens
import org.springframework.security.oauth2.jwt.Jwt
import java.time.OffsetDateTime
import java.util.UUID

/** The provisioned counterpart of [TestTokens] for MVC slices that mock [UserService]. */
object TestUsers {
    val ALICE_ID: UUID = UUID.fromString("7d1e2f3a-4b5c-4d6e-8f90-a1b2c3d4e5f6")

    val alice = User(
        id = ALICE_ID,
        issuer = TestTokens.ISSUER,
        subject = TestTokens.SUBJECT,
        username = TestTokens.USERNAME,
        email = TestTokens.EMAIL,
        createdAt = OffsetDateTime.parse("2026-09-01T08:00:00Z"),
        lastSeenAt = OffsetDateTime.parse("2026-09-07T10:00:00Z"),
    )

    /** Mirrors what the real service would derive from the token, so `sub`/`preferred_username` stay in sync. */
    fun fromJwt(jwt: Jwt): User {
        val subject = requireNotNull(jwt.subject) { "test token has no subject" }
        return alice.copy(
            id = if (subject == TestTokens.SUBJECT) ALICE_ID else UUID.nameUUIDFromBytes(subject.toByteArray()),
            issuer = jwt.issuer.toString(),
            subject = subject,
            username = jwt.getClaimAsString("preferred_username") ?: subject,
            email = jwt.getClaimAsString("email"),
        )
    }

    /** Makes the mocked [UserService] provision any authenticated request the way the real one would. */
    fun stubProvisioning(users: UserService) {
        every { users.provision(any()) } answers { fromJwt(firstArg()) }
    }
}
