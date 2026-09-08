import type { components, operations, paths } from './generated/schema'

export type { components, operations, paths }

type Schemas = components['schemas']

export type AgentRunResponse = Schemas['AgentRunResponse']
export type AgentRunEvent = Schemas['AgentRunEvent']
export type AgentRunEventType = NonNullable<AgentRunEvent['type']>
export type AgentKind = NonNullable<AgentRunResponse['kind']>
export type AgentPhase = NonNullable<AgentRunResponse['phase']>
export type RunSource = NonNullable<AgentRunResponse['source']>
export type RunCreatorResponse = Schemas['RunCreatorResponse']
export type CreateAgentRunRequest = Schemas['CreateAgentRunRequest']
export type PageResponseAgentRunResponse = Schemas['PageResponseAgentRunResponse']

export type AuthConfigResponse = Schemas['AuthConfigResponse']
export type UserResponse = Schemas['UserResponse']

export type RepositoryResponse = Schemas['RepositoryResponse']
export type CreateRepositoryRequest = Schemas['CreateRepositoryRequest']
export type UpdateRepositoryRequest = Schemas['UpdateRepositoryRequest']

export type ReviewResultResponse = Schemas['ReviewResultResponse']
export type ReviewResultSummaryResponse = Schemas['ReviewResultSummaryResponse']
export type ReviewFindingResponse = Schemas['ReviewFindingResponse']
export type Severity = NonNullable<ReviewFindingResponse['severity']>
export type PageResponseReviewResultSummaryResponse =
  Schemas['PageResponseReviewResultSummaryResponse']

export type ProblemDetail = Schemas['ProblemDetail']

export type AgentRunListQuery = NonNullable<operations['agentRunList']['parameters']['query']>
export type AgentWatchQuery = NonNullable<operations['agentWatchWatch']['parameters']['query']>
export type ReviewResultListQuery = NonNullable<
  operations['reviewResultList']['parameters']['query']
>
export type ReviewFindingsQuery = NonNullable<
  operations['reviewResultFindings']['parameters']['query']
>

export const AGENT_PHASES = [
  'CREATED',
  'PENDING',
  'RUNNING',
  'SUCCESS',
  'FAILED',
  'CANCELLED',
] as const satisfies readonly AgentPhase[]

export const TERMINAL_AGENT_PHASES = [
  'SUCCESS',
  'FAILED',
  'CANCELLED',
] as const satisfies readonly AgentPhase[]

export const SEVERITIES = [
  'BLOCKER',
  'MAJOR',
  'MINOR',
  'INFO',
] as const satisfies readonly Severity[]

export function isTerminalPhase(phase: AgentPhase | undefined): boolean {
  return phase !== undefined && (TERMINAL_AGENT_PHASES as readonly AgentPhase[]).includes(phase)
}
