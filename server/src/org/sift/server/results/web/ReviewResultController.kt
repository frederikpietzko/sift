package org.sift.server.results.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.sift.events.Severity
import org.sift.server.api.PageResponse
import org.sift.server.results.ReviewResultFilter
import org.sift.server.results.ReviewResultService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Read-only view of ingested review results; rows are only ever created by the `code-review.completed` consumer. */
@Tag(name = "results", description = "Review results and their findings")
@RestController
@RequestMapping("/api/v1/results")
class ReviewResultController(private val service: ReviewResultService) {
    @Operation(summary = "List review results")
    @GetMapping
    fun list(
        @RequestParam(required = false) repositoryUrl: String?,
        @RequestParam(required = false) commitSha: String?,
        @RequestParam(required = false) agentRunId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<ReviewResultSummaryResponse> {
        val filter = ReviewResultFilter(repositoryUrl = repositoryUrl, commitSha = commitSha, agentRunId = agentRunId)
        val results = service.list(filter, page, size)
        val counts = service.findingCounts(results.items.map { it.id })
        return PageResponse.from(results) { ReviewResultSummaryResponse.from(it, counts[it.id] ?: 0L) }
    }

    @Operation(summary = "Get a review result with all its findings")
    @ApiResponse(responseCode = "200", description = "The result including its findings")
    @ApiResponse(responseCode = "404", description = "Result not found")
    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ReviewResultResponse =
        ReviewResultResponse.from(service.get(id), service.findings(id))

    @Operation(
        summary = "List the findings of a review result",
        description = "Optionally narrowed by severity and file",
    )
    @ApiResponse(responseCode = "200", description = "Matching findings")
    @ApiResponse(responseCode = "404", description = "Result not found")
    @GetMapping("/{id}/findings")
    fun findings(
        @PathVariable id: UUID,
        @RequestParam(required = false) severity: Severity?,
        @RequestParam(required = false) file: String?,
    ): List<ReviewFindingResponse> = service.findings(id, severity, file).map(ReviewFindingResponse::from)
}
