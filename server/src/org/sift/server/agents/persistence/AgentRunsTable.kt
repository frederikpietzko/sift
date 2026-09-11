package org.sift.server.agents.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * Exposed DSL mapping of `agent_runs`. `repository_id` and `created_by` are foreign keys to `repositories` and
 * `users` in the Flyway schema; they are plain columns here so this module does not reach into the persistence
 * internals of the other modules.
 */
object AgentRunsTable : Table("agent_runs") {
    val id = uuid("id")
    val kind = text("kind")
    /** Named `runSource` because `source` is already a member of Exposed's `ColumnSet`. */
    val runSource = text("source").default("API")
    val repositoryId = uuid("repository_id").nullable()
    val crName = text("cr_name")
    val crUid = text("cr_uid").nullable().uniqueIndex()
    val generation = long("generation").nullable()
    val executionId = text("execution_id").nullable()
    val phase = text("phase")
    val reason = text("reason").nullable()
    val message = text("message").nullable()
    val spec = jsonb<JsonNode>(
        name = "spec",
        serialize = SPEC_MAPPER::writeValueAsString,
        deserialize = SPEC_MAPPER::readTree,
    )
    val createdAt = timestampWithTimeZone("created_at")
    val startedAt = timestampWithTimeZone("started_at").nullable()
    val completedAt = timestampWithTimeZone("completed_at").nullable()
    val observedAt = timestampWithTimeZone("observed_at").nullable()
    val updatedAt = timestampWithTimeZone("updated_at")
    /** `null` for `EXTERNAL` runs that were first seen through a status event rather than requested via the API. */
    val createdBy = uuid("created_by").nullable()
    /** Self reference to the predecessor of a revised run; `on delete set null` in the Flyway schema. */
    val supersedesRunId = uuid("supersedes_run_id").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(customIndexName = "agent_runs_phase", isUnique = false, phase, createdAt)
        index(customIndexName = "agent_runs_created_by", isUnique = false, createdBy, createdAt)
        index(customIndexName = "agent_runs_supersedes_run_id", isUnique = false, supersedesRunId)
    }
}

private val SPEC_MAPPER: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()
