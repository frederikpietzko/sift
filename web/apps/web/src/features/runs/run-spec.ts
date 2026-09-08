import type { AgentPhase, AgentRunResponse } from '@sift/api-client'

/** Client-side view of the server's `CodeReviewRunSpec` (the API documents `spec` as free-form). */
export interface CodeReviewSpec {
  repositoryUrl: string | undefined
  branch: string | undefined
  baseBranch: string | undefined
  commitSha: string | undefined
  pullRequest: string | undefined
}

function optionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

export function readCodeReviewSpec(run: AgentRunResponse | undefined): CodeReviewSpec {
  const spec = run?.spec ?? {}
  return {
    repositoryUrl: optionalString(spec.repositoryUrl),
    branch: optionalString(spec.branch),
    baseBranch: optionalString(spec.baseBranch),
    commitSha: optionalString(spec.commitSha),
    pullRequest: optionalString(spec.pullRequest),
  }
}

export function shortSha(sha: string | undefined, length = 7): string {
  return sha ? sha.slice(0, length) : '—'
}

/** `createdBy` is `null` for `EXTERNAL` runs (created via CRD/webhook, not through the API). */
export function creatorLabel(run: AgentRunResponse | undefined): string {
  return run?.createdBy?.username ?? 'external'
}

const KIND_LABELS: Record<NonNullable<AgentRunResponse['kind']>, string> = {
  CODE_REVIEW: 'Code review',
}

export function kindLabel(kind: AgentRunResponse['kind']): string {
  return kind ? KIND_LABELS[kind] : 'Unknown'
}

const PHASE_LABELS: Record<AgentPhase, string> = {
  CREATED: 'Created',
  PENDING: 'Pending',
  RUNNING: 'Running',
  SUCCESS: 'Succeeded',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
}

export function phaseLabel(phase: AgentPhase | undefined): string {
  return phase ? PHASE_LABELS[phase] : 'Unknown'
}
