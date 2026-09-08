package org.sift.server.agents

import org.sift.server.agents.persistence.AgentRunRepository
import org.sift.server.repositories.RepositoryUsageCheck
import org.springframework.stereotype.Component
import java.util.UUID

/** Vetoes deleting a repository while one of its runs is still in a non-terminal phase (`409` on the API). */
@Component
class AgentRunRepositoryUsageCheck(private val runs: AgentRunRepository) : RepositoryUsageCheck {
    override fun usage(repositoryId: UUID): String? =
        if (runs.hasActiveRuns(repositoryId)) ACTIVE_RUNS else null

    companion object {
        const val ACTIVE_RUNS = "active agent runs"
    }
}
