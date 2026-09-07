package org.sift.server

import org.sift.server.config.ServerProperties
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var properties: ServerProperties

    @Test
    fun `context loads with mocked kubernetes client and bound properties`() {
        assertEquals("sift-dev", properties.namespace)
        assertEquals("sift-repo-", properties.secretPrefix)
        assertEquals(ENCRYPTION_KEY, properties.encryptionKey)
        assertTrue(kubernetesClient.toString().isNotEmpty())
    }

    @Test
    fun `flyway applied the initial migration`() {
        val versions = jdbcTemplate.queryForList(
            "select version from flyway_schema_history where success order by installed_rank",
            String::class.java,
        )
        assertEquals(listOf("1"), versions)
    }

    @Test
    fun `initial schema contains all tables and the agent runs trigger`() {
        val tables = jdbcTemplate.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public'",
            String::class.java,
        ).toSet()
        assertTrue(
            tables.containsAll(listOf("repositories", "agent_runs", "review_results", "review_findings")),
            tables.toString(),
        )

        val triggers = jdbcTemplate.queryForList(
            "select tgname from pg_trigger where not tgisinternal",
            String::class.java,
        )
        assertEquals(listOf("agent_runs_notify"), triggers)
    }
}
