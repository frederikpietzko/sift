package org.sift.server.agents.watch

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.springframework.stereotype.Component

/**
 * In-process fan-out of agent run changes. There is no replay: a subscriber sees only events emitted after it
 * subscribed, so watchers load a snapshot first (see `AgentRunWatchService`). Slow subscribers do not block the
 * producer; when the buffer is full the oldest event is dropped, which is acceptable because every event
 * carries the full run and the next one supersedes it.
 */
@Component
class AgentRunEvents {
    private val _events = MutableSharedFlow<AgentRunEvent>(
        replay = 0,
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<AgentRunEvent> = _events.asSharedFlow()

    /** Never suspends and never fails: with `DROP_OLDEST` `tryEmit` always succeeds. */
    fun emit(event: AgentRunEvent) {
        _events.tryEmit(event)
    }

    companion object {
        const val BUFFER_CAPACITY = 256
    }
}
