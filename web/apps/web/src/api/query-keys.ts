import type {
  AgentRunListQuery,
  ReviewFindingsQuery,
  ReviewResultListQuery,
} from '@sift/api-client'

/**
 * Single source of query keys so cache invalidation and SSE cache patching (later steps) never
 * disagree on the key shape. Keys are hierarchical: invalidating `agents.all` covers lists + details.
 */
export const queryKeys = {
  auth: {
    config: ['auth', 'config'] as const,
  },
  me: ['me'] as const,
  repositories: {
    all: ['repositories'] as const,
    list: () => ['repositories', 'list'] as const,
    detail: (id: string) => ['repositories', 'detail', id] as const,
  },
  agents: {
    all: ['agents'] as const,
    /** Prefix of every run list query (used by SSE cache patching). */
    lists: ['agents', 'list'] as const,
    list: (query: AgentRunListQuery = {}) => ['agents', 'list', query] as const,
    detail: (id: string) => ['agents', 'detail', id] as const,
  },
  results: {
    all: ['results'] as const,
    list: (query: ReviewResultListQuery = {}) => ['results', 'list', query] as const,
    detail: (id: string) => ['results', 'detail', id] as const,
    findings: (id: string, query: ReviewFindingsQuery = {}) =>
      ['results', 'detail', id, 'findings', query] as const,
  },
}
