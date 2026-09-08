import type {
  CreateRepositoryRequest,
  RepositoryResponse,
  UpdateRepositoryRequest,
} from '@sift/api-client'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { siftClient } from '@/api/client'
import { queryKeys } from '@/api/query-keys'

export function useRepositories() {
  return useQuery({
    queryKey: queryKeys.repositories.list(),
    queryFn: async () => {
      const { data } = await siftClient.GET('/api/v1/repositories')
      return data ?? []
    },
  })
}

export function useRepository(id: string, enabled = true) {
  return useQuery({
    queryKey: queryKeys.repositories.detail(id),
    queryFn: async () => {
      const { data } = await siftClient.GET('/api/v1/repositories/{id}', {
        params: { path: { id } },
      })
      return data
    },
    enabled,
  })
}

export function useCreateRepository() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: CreateRepositoryRequest) => {
      const { data } = await siftClient.POST('/api/v1/repositories', { body })
      return data as RepositoryResponse
    },
    onSuccess: (repository) => {
      if (repository?.id) {
        queryClient.setQueryData(queryKeys.repositories.detail(repository.id), repository)
      }
      return queryClient.invalidateQueries({ queryKey: queryKeys.repositories.all })
    },
  })
}

export interface UpdateRepositoryVariables {
  id: string
  body: UpdateRepositoryRequest
}

export function useUpdateRepository() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, body }: UpdateRepositoryVariables) => {
      const { data } = await siftClient.PUT('/api/v1/repositories/{id}', {
        params: { path: { id } },
        body,
      })
      return data as RepositoryResponse
    },
    onSuccess: (repository, { id }) => {
      queryClient.setQueryData(queryKeys.repositories.detail(id), repository)
      return queryClient.invalidateQueries({ queryKey: queryKeys.repositories.all })
    },
  })
}

export function useDeleteRepository() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (id: string) => {
      await siftClient.DELETE('/api/v1/repositories/{id}', { params: { path: { id } } })
    },
    onSuccess: (_result, id) => {
      queryClient.removeQueries({ queryKey: queryKeys.repositories.detail(id) })
      return queryClient.invalidateQueries({ queryKey: queryKeys.repositories.all })
    },
  })
}
