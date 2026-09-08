package org.sift.server.agents.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
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
import org.springframework.web.bind.annotation.DeleteMapping
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
@Tag(name = "agents", description = "Agent runs: trigger, list, inspect, cancel and delete")
@RestController
@RequestMapping("/api/v1/agents")
class AgentRunController(private val service: AgentRunService) {
    @Operation(summary = "Start an agent run")
    @ApiResponse(responseCode = "202", description = "Run accepted; `Location` points at the new run")
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "404", description = "Repository not found")
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateAgentRunRequest,
        @CurrentUser user: User,
    ): ResponseEntity<AgentRunResponse> {
        val run = service.create(request, user)
        val location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").build(run.id)
        return ResponseEntity.accepted().location(location).body(AgentRunResponse.from(run))
    }

    @Operation(summary = "List agent runs")
    @ApiResponse(responseCode = "200", description = "One page of runs, newest first")
    @ApiResponse(responseCode = "400", description = "`mine` and `createdBy` name different users")
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

    @Operation(summary = "Get an agent run")
    @ApiResponse(responseCode = "200", description = "The run")
    @ApiResponse(responseCode = "404", description = "Run not found")
    @GetMapping("/{id:$UUID_PATTERN}")
    fun get(@PathVariable id: UUID): AgentRunResponse = AgentRunResponse.from(service.get(id))

    @Operation(summary = "Cancel an agent run")
    @ApiResponse(responseCode = "202", description = "Cancellation requested")
    @ApiResponse(responseCode = "404", description = "Run not found")
    @ApiResponse(responseCode = "409", description = "Run already reached a terminal phase")
    @PostMapping("/{id:$UUID_PATTERN}/cancel")
    fun cancel(@PathVariable id: UUID): ResponseEntity<AgentRunResponse> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(AgentRunResponse.from(service.cancel(id)))

    @Operation(
        summary = "Delete an agent run",
        description = "A run that is still active is cancelled first; its review result and findings are deleted too.",
    )
    @ApiResponse(responseCode = "204", description = "Run deleted")
    @ApiResponse(responseCode = "404", description = "Run not found")
    @DeleteMapping("/{id:$UUID_PATTERN}")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        service.delete(id)
        return ResponseEntity.noContent().build()
    }

    companion object {
        const val UUID_PATTERN = "[0-9a-fA-F-]{36}"
    }
}
