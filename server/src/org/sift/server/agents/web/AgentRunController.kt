package org.sift.server.agents.web

import jakarta.validation.Valid
import org.sift.server.agents.AgentKind
import org.sift.server.agents.AgentPhase
import org.sift.server.agents.AgentRunFilter
import org.sift.server.agents.AgentRunService
import org.sift.server.api.PageResponse
import org.sift.server.users.CurrentUser
import org.sift.server.users.User
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

/**
 * The literal `/api/v1/agents/watch` (SSE, `AgentWatchController`) is more specific than `/{id}` and wins the
 * mapping; the UUID pattern on `{id}` additionally guarantees `watch` can never be parsed as a run id (`406`)
 * when a client omits the `text/event-stream` accept header.
 *
 * Listing accepts `mine=true` (only the caller's runs) or `createdBy=<user id>`; both together are only allowed
 * when they name the same user.
 */
@RestController
@RequestMapping("/api/v1/agents")
class AgentRunController(private val service: AgentRunService) {
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateAgentRunRequest,
        @CurrentUser user: User,
    ): ResponseEntity<AgentRunResponse> {
        val run = service.create(request, user)
        val location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").build(run.id)
        return ResponseEntity.accepted().location(location).body(AgentRunResponse.from(run))
    }

    @GetMapping
    @Suppress("LongParameterList") // one parameter per query string filter
    fun list(
        @RequestParam(required = false) kind: AgentKind?,
        @RequestParam(required = false) phase: AgentPhase?,
        @RequestParam(required = false) repositoryId: UUID?,
        @RequestParam(defaultValue = "false") mine: Boolean,
        @RequestParam(required = false) createdBy: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @CurrentUser user: User,
    ): PageResponse<AgentRunResponse> {
        val filter = AgentRunFilter(
            kind = kind,
            phase = phase,
            repositoryId = repositoryId,
            createdBy = AgentRunFilter.resolveCreatedBy(mine = mine, createdBy = createdBy, user = user),
        )
        return PageResponse.from(service.list(filter, page, size), AgentRunResponse::from)
    }

    @GetMapping("/{id:$UUID_PATTERN}")
    fun get(@PathVariable id: UUID): AgentRunResponse = AgentRunResponse.from(service.get(id))

    @PostMapping("/{id:$UUID_PATTERN}/cancel")
    fun cancel(@PathVariable id: UUID): ResponseEntity<AgentRunResponse> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(AgentRunResponse.from(service.cancel(id)))

    companion object {
        const val UUID_PATTERN = "[0-9a-fA-F-]{36}"
    }
}
