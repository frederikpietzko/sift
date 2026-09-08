package org.sift.server.users.web

import org.sift.server.users.User
import java.util.UUID

data class UserResponse(
    val id: UUID,
    val subject: String,
    val issuer: String,
    val username: String,
    val email: String?,
) {
    companion object {
        fun from(user: User): UserResponse = UserResponse(
            id = user.id,
            subject = user.subject,
            issuer = user.issuer,
            username = user.username,
            email = user.email,
        )
    }
}
