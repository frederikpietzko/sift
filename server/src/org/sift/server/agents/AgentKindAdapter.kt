package org.sift.server.agents

import java.util.UUID

/** Identity of the custom resource an adapter applied; `crUid` is `null` when the API server returned none. */
data class AppliedResource(val crName: String, val crUid: String?)

/** Translates an [AgentRun] of one [AgentKind] into its Kubernetes custom resource and back. */
interface AgentKindAdapter {
    val kind: AgentKind

    /** Deterministic CR name for a run, known before the CR exists so it can be persisted with the run. */
    fun resourceName(runId: UUID): String

    fun apply(run: AgentRun, request: CreateAgentRunRequest): AppliedResource

    /** Deletes the CR behind [run]; a missing resource is not an error. */
    fun delete(run: AgentRun)
}
