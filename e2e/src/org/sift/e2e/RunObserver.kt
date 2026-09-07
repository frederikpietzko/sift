package org.sift.e2e

import org.sift.e2e.SseFrameReader.Companion.payload
import tools.jackson.databind.JsonNode

/** Outcome of following a run over SSE until it reached a terminal phase. */
data class RunOutcome(val phases: List<String>, val finalRun: JsonNode) {
    val finalPhase: String get() = finalRun.path("phase").asString()
}

/**
 * Consumes `UPDATED` watch frames and records the sequence of phases until the run is terminal.
 * Only stable contracts are checked: duplicate frames are tolerated, `PENDING` is optional, and
 * a run that ends in `FAILED`/`CANCELLED` surfaces its `reason`/`message` in the failure text so
 * the cause (for example `ImagePullFailed`) is visible without opening logs.
 */
object RunObserver {
    const val SUCCESS = "SUCCESS"
    const val RUNNING = "RUNNING"
    val terminalPhases: Set<String> = setOf(SUCCESS, "FAILED", "CANCELLED")

    /**
     * Reads frames until a terminal phase is seen; frames that are not `UPDATED` (e.g. a repeated
     * `SNAPSHOT`) are ignored. Throws [RunFailedException] on a non-success terminal phase.
     */
    fun followUntilTerminal(frames: Sequence<SseFrame>): RunOutcome {
        val phases = mutableListOf<String>()
        for (frame in frames) {
            if (frame.event != "UPDATED") continue
            val run = frame.payload()
            val phase = run.path("phase").asString()
            if (phases.lastOrNull() != phase) phases += phase
            if (phase in terminalPhases) {
                if (phase != SUCCESS) throw RunFailedException(phase, run, phases)
                return RunOutcome(phases, run)
            }
        }
        error("SSE stream ended before the run reached a terminal phase; observed: $phases")
    }
}

/** The run finished in a terminal phase other than `SUCCESS`. */
class RunFailedException(phase: String, run: JsonNode, phases: List<String>) : RuntimeException(
    "Run ended in $phase (reason=${run.path("reason").asString()}, message=${run.path("message").asString()}); " +
        "phases observed: $phases",
)
