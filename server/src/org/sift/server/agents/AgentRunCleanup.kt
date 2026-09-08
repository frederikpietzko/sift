package org.sift.server.agents

import java.util.UUID

/**
 * Lets other modules drop the data they attached to a run before [AgentRunService.delete] removes it.
 * Implementations run inside the deleting transaction; `results` uses it to delete the review result (and
 * its findings) that references the run, which the schema's foreign key would otherwise reject.
 */
interface AgentRunCleanup {
    fun deleteForRun(runId: UUID)
}
