package org.sift.server.agents.watch

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.sift.server.agents.AgentKind
import org.sift.server.agents.AgentRunFilter
import org.sift.server.config.ServerProperties
import org.sift.server.users.CurrentUser
import org.sift.server.users.User
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID
import kotlin.time.toKotlinDuration

/**
 * `GET /api/v1/agents/watch` — Server-Sent Events stream of agent run changes. Each data event is named after
 * its [AgentRunEventType] and carries the run's `updatedAt` epoch millis as `id`, so a browser `EventSource`
 * resumes via `Last-Event-ID` and only the runs written after that point are replayed as `SNAPSHOT`. Comment
 * frames (`:heartbeat`) keep idle connections alive through proxies. Spring MVC adapts the returned `Flow`
 * to an `SseEmitter` (`kotlinx-coroutines-reactor`); `spring.mvc.async.request-timeout=-1` keeps it open.
 * `mine=true` / `createdBy` narrow both the snapshot and the live events to one creator, like the list endpoint.
 */
@Tag(name = "agents")
@RestController
@RequestMapping("/api/v1/agents/watch")
class AgentWatchController(
    private val watches: AgentRunWatchService,
    properties: ServerProperties,
) {
    private val heartbeat = properties.watch.heartbeat.toKotlinDuration()

    @Operation(
        summary = "Watch agent runs (Server-Sent Events)",
        description = "Open-ended `text/event-stream`. Each event is named after `AgentRunEvent.type` (`SNAPSHOT` " +
            "or `UPDATED`), carries an `AgentRunEvent` JSON payload as `data` and the run's `updatedAt` epoch millis " +
            "as `id`; send it back as `Last-Event-ID` to resume. `:heartbeat` comment frames keep the stream alive.",
    )
    @ApiResponse(
        responseCode = "200",
        description = "Event stream; each `data` frame is one `AgentRunEvent`",
        content = [
            Content(
                mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                schema = Schema(implementation = AgentRunEvent::class),
            ),
        ],
    )
    @ApiResponse(responseCode = "400", description = "`mine` and `createdBy` name different users")
    @GetMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Suppress("LongParameterList") // one parameter per query string filter
    fun watch(
        @RequestParam(required = false) agentId: UUID?,
        @RequestParam(required = false) kind: AgentKind?,
        @RequestParam(defaultValue = "false") mine: Boolean,
        @RequestParam(required = false) createdBy: UUID?,
        @RequestHeader(LAST_EVENT_ID, required = false) lastEventId: String?,
        @CurrentUser user: User,
    ): Flow<ServerSentEvent<AgentRunEvent>> {
        val request = WatchRequest(
            agentId = agentId,
            kind = kind,
            since = lastEventId.toResumePoint(),
            createdBy = AgentRunFilter.resolveCreatedBy(mine = mine, createdBy = createdBy, user = user),
        )
        return merge(watches.watch(request).map { it.toServerSentEvent() }, heartbeats())
    }

    private fun heartbeats(): Flow<ServerSentEvent<AgentRunEvent>> = flow {
        while (true) {
            delay(heartbeat)
            emit(ServerSentEvent.builder<AgentRunEvent>().comment(HEARTBEAT).build())
        }
    }

    companion object {
        const val LAST_EVENT_ID = "Last-Event-ID"
        const val HEARTBEAT = "heartbeat"

        fun AgentRunEvent.toServerSentEvent(): ServerSentEvent<AgentRunEvent> = ServerSentEvent.builder(this)
            .id(run.updatedAt.toInstant().toEpochMilli().toString())
            .event(type.name)
            .build()

        /** A malformed or absent `Last-Event-ID` simply yields a full snapshot. */
        fun String?.toResumePoint(): Instant? = this?.trim()?.toLongOrNull()?.let(Instant::ofEpochMilli)
    }
}
