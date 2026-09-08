package org.sift.server.users

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/** Blocking Exposed DSL access to `users`; callers own the Spring transaction. */
@org.springframework.stereotype.Repository
class UserRepository {
    /**
     * Inserts [user] or, when its `(issuer, subject)` is already known, refreshes username, email and
     * `last_seen_at` of the existing row. Returns the stored row, i.e. the original id and `created_at` survive.
     */
    fun upsert(user: User): User {
        UsersTable.upsert(
            UsersTable.issuer,
            UsersTable.subject,
            onUpdate = {
                it[UsersTable.username] = insertValue(UsersTable.username)
                it[UsersTable.email] = insertValue(UsersTable.email)
                it[UsersTable.lastSeenAt] = insertValue(UsersTable.lastSeenAt)
            },
        ) {
            it[id] = user.id.toKotlinUuid()
            it[issuer] = user.issuer
            it[subject] = user.subject
            it[username] = user.username
            it[email] = user.email
            it[createdAt] = user.createdAt
            it[lastSeenAt] = user.lastSeenAt
        }
        return checkNotNull(findByIdentity(user.issuer, user.subject)) { "Upsert of user ${user.subject} left no row" }
    }

    fun findById(id: UUID): User? =
        UsersTable.selectAll().where { UsersTable.id eq id.toKotlinUuid() }.singleOrNull()?.toUser()

    fun findByIdentity(issuer: String, subject: String): User? = UsersTable
        .selectAll()
        .where { (UsersTable.issuer eq issuer) and (UsersTable.subject eq subject) }
        .singleOrNull()
        ?.toUser()

    private fun ResultRow.toUser(): User = User(
        id = this[UsersTable.id].toJavaUuid(),
        issuer = this[UsersTable.issuer],
        subject = this[UsersTable.subject],
        username = this[UsersTable.username],
        email = this[UsersTable.email],
        createdAt = this[UsersTable.createdAt],
        lastSeenAt = this[UsersTable.lastSeenAt],
    )
}
