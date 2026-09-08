package org.sift.server.results

import org.sift.server.agents.AgentRunCleanup
import org.sift.server.results.persistence.ReviewResultRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Deletes the review result (and, through the schema's cascade, its findings) of a run that is being
 * removed. Without it the `review_results.agent_run_id` foreign key would reject the deletion.
 */
@Component
class ReviewResultRunCleanup(private val results: ReviewResultRepository) : AgentRunCleanup {
    private val log = LoggerFactory.getLogger(ReviewResultRunCleanup::class.java)

    override fun deleteForRun(runId: UUID) {
        val deleted = results.deleteByAgentRunId(runId)
        if (deleted > 0) {
            log.info("Deleted {} review result(s) of run {}", deleted, runId)
        }
    }
}
