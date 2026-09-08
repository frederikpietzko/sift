import {
  watchAgentRuns,
  type AgentRunEvent,
  type AgentRunListQuery,
  type AgentRunResponse,
  type AgentWatchQuery,
  type CreateAgentRunRequest,
  type PageResponseAgentRunResponse,
} from '@sift/api-client'
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'

import { siftClient } from '@/api/client'
import { queryKeys } from '@/api/query-keys'

export const DEFAULT_PAGE_SIZE = 20

const EMPTY_PAGE: Required<PageResponseAgentRunResponse> = {
  items: [],
  page: 0,
  size: DEFAULT_PAGE_SIZE,
  total: 0,
}

export function useAgentRuns(query: AgentRunListQuery = {}) {
  return useQuery({
    queryKey: queryKeys.agents.list(query),
    queryFn: async () => {
      const { data } = await siftClient.GET('/api/v1/agents', { params: { query } })
      return { ...EMPTY_PAGE, ...data }
    },
    placeholderData: (previous) => previous,
  })
}

export function useAgentRun(id: string, enabled = true) {
  return useQuery({
    queryKey: queryKeys.agents.detail(id),
    queryFn: async () => {
      const { data } = await siftClient.GET('/api/v1/agents/{id}', { params: { path: { id } } })
      return data as AgentRunResponse
    },
    enabled,
  })
}

export function useCreateAgentRun() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: CreateAgentRunRequest) => {
      const { data } = await siftClient.POST('/api/v1/agents', { body })
      return data as AgentRunResponse
    },
    onSuccess: (run) => {
      if (run?.id) queryClient.setQueryData(queryKeys.agents.detail(run.id), run)
      return queryClient.invalidateQueries({ queryKey: queryKeys.agents.all })
    },
  })
}

export function useCancelAgentRun() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (id: string) => {
      const { data } = await siftClient.POST('/api/v1/agents/{id}/cancel', {
        params: { path: { id } },
      })
      return data as AgentRunResponse
    },
    onSuccess: (run) => applyRunUpdate(queryClient, run),
  })
}

/**
 * Deletes a run: the server cancels it first when it is still active and removes its review result
 * and findings. Drops the detail cache entry and refreshes every cached (filtered/paginated) list.
 */
export function useDeleteAgentRun() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (id: string) => {
      await siftClient.DELETE('/api/v1/agents/{id}', { params: { path: { id } } })
    },
    onSuccess: (_result, id) => {
      queryClient.removeQueries({ queryKey: queryKeys.agents.detail(id) })
      return queryClient.invalidateQueries({ queryKey: queryKeys.agents.all })
    },
  })
}

export type WatchStatus = 'connecting' | 'live' | 'reconnecting' | 'stopped'

export interface UseAgentRunWatchOptions {
  /** Server-side narrowing (`agentId`, `kind`, `mine`); the cache patching is filter-agnostic. */
  query?: AgentWatchQuery
  enabled?: boolean
}

/**
 * Keeps the agent run caches live: consumes `GET /api/v1/agents/watch` through the shared client
 * (bearer token + reconnect with `Last-Event-ID` are handled by `watchAgentRuns`) and patches the
 * detail and list queries in place. When resuming after a disconnect the replayed `SNAPSHOT` frames
 * are applied like `UPDATED` ones so missed transitions are not lost.
 */
export function useAgentRunWatch({ query, enabled = true }: UseAgentRunWatchOptions = {}) {
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<WatchStatus>('connecting')
  const lastEventId = useRef<string | undefined>(undefined)
  // serialise so a structurally equal filter object does not restart the stream
  const querySerialized = JSON.stringify(query ?? {})

  useEffect(() => {
    if (!enabled) return
    const watchQuery = JSON.parse(querySerialized) as AgentWatchQuery
    const controller = new AbortController()
    let resuming = lastEventId.current !== undefined

    void watchAgentRuns(siftClient, {
      signal: controller.signal,
      query: watchQuery,
      lastEventId: lastEventId.current,
      onOpen: () => setStatus('live'),
      onError: () => {
        resuming = true
        setStatus('reconnecting')
      },
      onEvent: (event, message) => {
        lastEventId.current = message.lastEventId
        applyRunEvent(queryClient, event, { resuming })
      },
    }).catch(() => setStatus('stopped'))

    return () => controller.abort()
  }, [enabled, querySerialized, queryClient])

  return { status: enabled ? status : 'stopped' }
}

interface ApplyEventOptions {
  /** `true` while the stream replays what happened since `Last-Event-ID`. */
  resuming: boolean
}

export function applyRunEvent(
  queryClient: QueryClient,
  event: AgentRunEvent,
  { resuming }: ApplyEventOptions,
) {
  const run = event.run
  if (!run?.id) return
  if (event.type === 'UPDATED' || resuming) {
    applyRunUpdate(queryClient, run)
  } else {
    // initial snapshot: the lists load themselves, only refresh what is already cached
    queryClient.setQueryData(queryKeys.agents.detail(run.id), run)
    patchLists(queryClient, run, { invalidateWhenMissing: false })
  }
}

/** Writes `run` into its detail cache and every cached run list (patch in place or refresh). */
export function applyRunUpdate(queryClient: QueryClient, run: AgentRunResponse) {
  if (!run.id) return
  queryClient.setQueryData(queryKeys.agents.detail(run.id), run)
  patchLists(queryClient, run, { invalidateWhenMissing: true })
}

function patchLists(
  queryClient: QueryClient,
  run: AgentRunResponse,
  { invalidateWhenMissing }: { invalidateWhenMissing: boolean },
) {
  const lists = queryClient.getQueriesData<PageResponseAgentRunResponse>({
    queryKey: queryKeys.agents.lists,
  })
  for (const [key, page] of lists) {
    const listQuery = (key[2] ?? {}) as AgentRunListQuery
    const items = page?.items ?? []
    const index = items.findIndex((item) => item.id === run.id)
    const matches = matchesListQuery(run, listQuery)
    if (index === -1) {
      // a run we have not seen in this page: refetch if it belongs here (e.g. newly created)
      if (invalidateWhenMissing && matches && (listQuery.page ?? 0) === 0) {
        void queryClient.invalidateQueries({ queryKey: key, exact: true })
      }
      continue
    }
    if (!matches) {
      // e.g. phase filter no longer applies -> let the server decide what the page looks like now
      void queryClient.invalidateQueries({ queryKey: key, exact: true })
      continue
    }
    queryClient.setQueryData<PageResponseAgentRunResponse>(key, (current) => {
      if (!current?.items) return current
      const next = [...current.items]
      next[index] = run
      return { ...current, items: next }
    })
  }
}

/** Filters the list endpoint applies that we can evaluate client-side (`mine`/`createdBy` cannot). */
export function matchesListQuery(run: AgentRunResponse, query: AgentRunListQuery): boolean {
  if (query.kind && run.kind !== query.kind) return false
  if (query.phase && run.phase !== query.phase) return false
  if (query.repositoryId && run.repositoryId !== query.repositoryId) return false
  return true
}
