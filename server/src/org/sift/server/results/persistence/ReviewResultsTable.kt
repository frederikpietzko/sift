package org.sift.server.results.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** Exposed DSL mapping of `review_results`; `agent_run_id` references `agent_runs` in the Flyway schema only. */
object ReviewResultsTable : Table("review_results") {
    val id = uuid("id")
    val executionId = text("execution_id").uniqueIndex()
    val agentRunId = uuid("agent_run_id").nullable()
    val repositoryUrl = text("repository_url")
    val branch = text("branch")
    val baseBranch = text("base_branch")
    val commitSha = text("commit_sha")
    val pullRequest = text("pull_request").nullable()
    val summary = text("summary")
    val completedAt = timestampWithTimeZone("completed_at")
    val receivedAt = timestampWithTimeZone("received_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(customIndexName = "review_results_repo_commit", isUnique = false, repositoryUrl, commitSha)
    }
}
