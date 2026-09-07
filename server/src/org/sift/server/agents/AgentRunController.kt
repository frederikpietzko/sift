package org.sift.server.agents

import jakarta.validation.Valid
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
 */
@RestController
@RequestMapping("/api/v1/agents")
class AgentRunController(private val service: AgentRunService) {
    @PostMapping
    fun create(@Valid @RequestBody request: CreateAgentRunRequest): ResponseEntity<AgentRunResponse> {
        val run = service.create(request)
        val location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").build(run.id)
        return ResponseEntity.accepted().location(location).body(AgentRunResponse.from(run))
    }

    @GetMapping
    fun list(
        @RequestParam(required = false) kind: AgentKind?,
        @RequestParam(required = false) phase: AgentPhase?,
        @RequestParam(required = false) repositoryId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<AgentRunResponse> {
        val filter = AgentRunFilter(kind = kind, phase = phase, repositoryId = repositoryId)
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
