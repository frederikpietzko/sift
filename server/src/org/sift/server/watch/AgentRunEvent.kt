package org.sift.server.watch

import org.sift.server.agents.AgentRunResponse

/** `SNAPSHOT` events replay current state on (re)connect; `UPDATED` events follow live `agent_runs` writes. */
enum class AgentRunEventType {
    SNAPSHOT,
    UPDATED,
}

data class AgentRunEvent(
    val type: AgentRunEventType,
    val run: AgentRunResponse,
)
