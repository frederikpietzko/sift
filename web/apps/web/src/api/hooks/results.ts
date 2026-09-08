import type {
  PageResponseReviewResultSummaryResponse,
  ReviewFindingsQuery,
  ReviewResultListQuery,
  ReviewResultResponse,
} from '@sift/api-client'
import { useQuery } from '@tanstack/react-query'

import { siftClient } from '@/api/client'
import { queryKeys } from '@/api/query-keys'

export const DEFAULT_RESULT_PAGE_SIZE = 20

const EMPTY_PAGE: Required<PageResponseReviewResultSummaryResponse> = {
  items: [],
  page: 0,
  size: DEFAULT_RESULT_PAGE_SIZE,
  total: 0,
}

export function useReviewResults(query: ReviewResultListQuery = {}, enabled = true) {
  return useQuery({
    queryKey: queryKeys.results.list(query),
    queryFn: async () => {
      const { data } = await siftClient.GET('/api/v1/results', { params: { query } })
      return { ...EMPTY_PAGE, size: query.size ?? EMPTY_PAGE.size, ...data }
    },
    enabled,
    placeholderData: (previous) => previous,
  })
}

/** The review result produced by one run (at most one per run), if it has been received yet. */
export function useReviewResultForRun(agentRunId: string | undefined, enabled = true) {
  const results = useReviewResults(
    { agentRunId: agentRunId ?? '', size: 1 },
    enabled && Boolean(agentRunId),
  )
  return { ...results, result: results.data?.items[0] }
}

/** One result including its summary and the complete list of findings. */
export function useReviewResult(id: string, enabled = true) {
  return useQuery({
    queryKey: queryKeys.results.detail(id),
    queryFn: async () => {
      const { data } = await siftClient.GET('/api/v1/results/{id}', { params: { path: { id } } })
      return data as ReviewResultResponse
    },
    enabled,
  })
}

/** Findings of one result, optionally narrowed by `severity` and/or `file` on the server. */
export function useReviewFindings(id: string, query: ReviewFindingsQuery = {}, enabled = true) {
  return useQuery({
    queryKey: queryKeys.results.findings(id, query),
    queryFn: async () => {
      const { data } = await siftClient.GET('/api/v1/results/{id}/findings', {
        params: { path: { id }, query },
      })
      return data ?? []
    },
    enabled,
    placeholderData: (previous) => previous,
  })
}
