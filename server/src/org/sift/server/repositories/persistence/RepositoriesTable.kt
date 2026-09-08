package org.sift.server.repositories.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object RepositoriesTable : Table("repositories") {
    val id = uuid("id")
    val name = text("name").uniqueIndex()
    val url = text("url")
    val tokenCiphertext = binary("token_ciphertext").nullable()
    val tokenIv = binary("token_iv").nullable()
    val secretName = text("secret_name").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}
