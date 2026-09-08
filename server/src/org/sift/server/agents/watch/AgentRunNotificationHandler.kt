package org.sift.server.agents.watch

import org.sift.server.agents.persistence.AgentRunRepository
import org.sift.server.agents.web.AgentRunResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionOperations
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Turns one `sift_agent_runs` notification payload (`{id, phase, updatedAt}`) into an `UPDATED` event by
 * reloading the row through the pool, so subscribers always see the committed state and never the payload.
 * Blocking; runs on the listener's IO dispatcher. Bad payloads and lookup failures are logged and skipped —
 * they must not take the `LISTEN` connection down.
 */
@Component
class AgentRunNotificationHandler(
    private val runs: AgentRunRepository,
    private val transactions: TransactionOperations,
    private val events: AgentRunEvents,
    private val mapper: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(AgentRunNotificationHandler::class.java)

    @Suppress("TooGenericExceptionCaught")
    fun publish(payload: String) {
        try {
            val id = UUID.fromString(mapper.readTree(payload)["id"].asString())
            val run = transactions.execute { runs.findById(id) }
            if (run == null) {
                log.debug("Notification for unknown agent run {}; ignoring", id)
                return
            }
            events.emit(AgentRunEvent(AgentRunEventType.UPDATED, AgentRunResponse.from(run)))
        } catch (exception: Exception) {
            log.warn("Ignoring unprocessable agent run notification {}", payload, exception)
        }
    }
}
