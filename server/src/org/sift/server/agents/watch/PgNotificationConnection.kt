package org.sift.server.agents.watch

import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.jdbc.PgConnection
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.stereotype.Component
import java.sql.SQLException

/**
 * A dedicated, long-lived JDBC connection that `LISTEN`s on one Postgres channel. It is deliberately not
 * borrowed from the Hikari pool: `LISTEN` is bound to the session and the pool would reclaim (and reset)
 * the connection. All methods block and throw [SQLException] on connection loss.
 */
class PgNotificationConnection private constructor(private val connection: PgConnection) : AutoCloseable {
    /** Waits up to [timeoutMillis] for notifications and returns their payloads (possibly none). */
    @Throws(SQLException::class)
    fun poll(timeoutMillis: Int): List<String> =
        connection.getNotifications(timeoutMillis)?.map { it.parameter } ?: emptyList()

    override fun close() {
        connection.close()
    }

    /** Opens notification connections from the application's datasource coordinates. */
    @Component
    class Factory(private val connectionDetails: JdbcConnectionDetails) {
        @Throws(SQLException::class)
        fun open(channel: String): PgNotificationConnection {
            val dataSource = PGSimpleDataSource().apply {
                setUrl(connectionDetails.jdbcUrl)
                user = connectionDetails.username
                password = connectionDetails.password
                applicationName = APPLICATION_NAME
            }
            val connection = dataSource.connection.unwrap(PgConnection::class.java)
            try {
                connection.createStatement().use { it.execute("LISTEN ${connection.escapeIdentifier(channel)}") }
            } catch (exception: SQLException) {
                connection.close()
                throw exception
            }
            return PgNotificationConnection(connection)
        }
    }

    companion object {
        /** `application_name` of the listener session, visible in `pg_stat_activity`. */
        const val APPLICATION_NAME = "sift-server-watch"
    }
}
