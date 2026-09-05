package org.sift.agents.review

import org.sift.events.Severity

/**
 * Structured output produced by the [ReviewAgent] LLM call.
 */
data class ReviewResult(
    val summary: String,
    val findings: List<ReviewFinding>,
)

/**
 * A single finding reported by the LLM as part of a [ReviewResult].
 */
data class ReviewFinding(
    val file: String,
    val startLine: Int?,
    val endLine: Int?,
    val severity: Severity,
    val category: String?,
    val message: String,
    val suggestion: String?,
)
