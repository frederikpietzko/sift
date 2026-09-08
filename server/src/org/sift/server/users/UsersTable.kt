package org.sift.server.users

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object UsersTable : Table("users") {
    val id = uuid("id")
    val issuer = text("issuer")
    val subject = text("subject")
    val username = text("username")
    val email = text("email").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val lastSeenAt = timestampWithTimeZone("last_seen_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("users_issuer_subject", issuer, subject)
    }
}
