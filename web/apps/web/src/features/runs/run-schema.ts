import {
  AGENT_PHASES,
  type AgentPhase,
  type CreateAgentRunRequest,
  type UpdateAgentRunRequest,
} from '@sift/api-client'
import { z } from 'zod'

export const COMMIT_SHA_PATTERN = /^[0-9a-fA-F]{40}$/

const uuid = z.uuid()

/** Mirrors `CreateAgentRunRequest` validation (`@NotBlank`, 40-hex `@Pattern`). */
export const startReviewSchema = z.object({
  repositoryId: z.string().min(1, 'Choose a repository'),
  branch: z.string().trim().min(1, 'Branch is required'),
  baseBranch: z.string().trim().min(1, 'Base branch is required'),
  commitSha: z
    .string()
    .trim()
    .regex(COMMIT_SHA_PATTERN, 'Commit SHA must be 40 hexadecimal characters'),
  pullRequest: z.string().trim(),
})

export type StartReviewFormValues = z.infer<typeof startReviewSchema>

export function toCreateAgentRunRequest(values: StartReviewFormValues): CreateAgentRunRequest {
  return {
    kind: 'CODE_REVIEW',
    repositoryId: values.repositoryId,
    branch: values.branch.trim(),
    baseBranch: values.baseBranch.trim(),
    commitSha: values.commitSha.trim().toLowerCase(),
    pullRequest: values.pullRequest.trim() || null,
  }
}

/** Revision body of `PUT /api/v1/agents/{id}`: same fields as create, without `kind`. */
export function toUpdateAgentRunRequest(values: StartReviewFormValues): UpdateAgentRunRequest {
  return {
    repositoryId: values.repositoryId,
    branch: values.branch.trim(),
    baseBranch: values.baseBranch.trim(),
    commitSha: values.commitSha.trim().toLowerCase(),
    pullRequest: values.pullRequest.trim() || null,
  }
}

const phase = z.enum(AGENT_PHASES)

/** `/runs` URL search params; unknown/invalid values fall back to "no filter". */
export const runsSearchSchema = z.object({
  kind: z.literal('CODE_REVIEW').optional().catch(undefined),
  phase: phase.optional().catch(undefined),
  repositoryId: uuid.optional().catch(undefined),
  mine: z.boolean().optional().catch(undefined),
  page: z.number().int().min(0).optional().catch(undefined),
})

export type RunsSearch = z.infer<typeof runsSearchSchema>

export function isAgentPhase(value: string): value is AgentPhase {
  return (AGENT_PHASES as readonly string[]).includes(value)
}
