import { useQuery } from '@tanstack/react-query'

import { siftClient } from '@/api/client'
import { queryKeys } from '@/api/query-keys'

export function useMe(enabled = true) {
  return useQuery({
    queryKey: queryKeys.me,
    queryFn: async () => {
      const { data } = await siftClient.GET('/api/v1/me')
      return data
    },
    enabled,
    staleTime: 5 * 60_000,
  })
}
