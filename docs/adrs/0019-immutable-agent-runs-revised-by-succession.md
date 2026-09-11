# ADR 0019: Immutable agent runs revised by succession

## Status

Accepted.

## Context

The Runs screen supported create, read, cancel and delete but no update. Users need to
correct a mistyped branch, base branch, commit SHA or pull request without retyping the
whole trigger form.

An agent run is not a mutable record. Each run owns exactly one `CodeReview` custom
resource (`cr-<runId>`), that resource is provisioned immutably
([ADR 0007](0007-immutable-review-provisioning.md)), and its result is ingested once and
linked back to the run. Editing the spec of an existing run in place would therefore
describe work that never happened: the stored `review_results` row, its findings and the
`executionId` would keep referring to the old commit while the run claimed the new one.
Re-applying a mutated CR would also collide with the generation-safe lifecycle
([ADR 0008](0008-generation-safe-review-lifecycle.md)), which treats a new generation as
a replacement rather than an edit.

Runs sourced from outside the API (`EXTERNAL`, no repository id) have no server-side
ownership of a repository or credentials, so there is nothing to re-trigger from.

## Decision

- Keep `agent_runs` append-only. `PUT /api/v1/agents/{id}` does not mutate the addressed
  run; it validates the edited spec and inserts a **successor** run in phase `CREATED`,
  then applies a fresh `CodeReview`. The successor's new UUID makes `cr-<id>` unique, so
  no CR name can collide with the predecessor's.
- Store the lineage as a single self-referencing column `supersedes_run_id`
  (`REFERENCES agent_runs(id) ON DELETE SET NULL`, Flyway `V3`). The opposite direction,
  `supersededByRunId`, is derived by lookup instead of being stored, so deleting either
  side of the chain can never leave a dangling pointer or block the delete with an FK
  error.
- Cancel and delete only the predecessor's *cluster* resource when it is still active
  (phase `CANCELLED`, reason `SupersededByRevision`); leave terminal predecessors
  untouched. Late status events for the cancelled predecessor are harmless because
  `applyStatus` already ignores runs in a terminal phase.
- Deliberately do **not** invoke `AgentRunCleanup` from `revise`. The predecessor keeps
  its row, its `review_results` and its findings, so both old and new results stay
  comparable. `DELETE /api/v1/agents/{id}` remains the single cleanup path that cascades
  to results and findings.
- Reject `EXTERNAL` runs with `409` and unknown ids with `404`; return `201` with a
  `Location` header pointing at the successor.
- Reuse the create contract for the update body (`UpdateAgentRunRequest`, same fields
  minus `kind`) so the web form, its Zod schema and one shared dialog serve both create
  and edit mode.

## Consequences and verification

Revising is an explicit, auditable fork rather than an edit: history is complete, every
result stays attributable to the exact spec that produced it, and a superseded run whose
CR is long gone is still fully reviewable through
`GET /api/v1/results?agentRunId=`. The cost is that the runs list grows one row per
revision, and that "the current run" is a chain the UI has to render rather than a single
row; the run detail screen shows a Revisions section linking predecessor and successor.

Server unit tests cover revising terminal and active predecessors, the `EXTERNAL`
rejection and retention of the predecessor's result. The e2e scenario in
`CodeReviewHappyPathTest` revises a finished run, watches the successor through to
`SUCCESS` and asserts the predecessor's stored result is still retrievable by id and that
both directions of the chain resolve. See the [server component](../system-components/server.md)
and the [web UI component](../system-components/web-ui.md).
