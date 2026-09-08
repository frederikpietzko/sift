import { useQuery } from '@tanstack/react-query'

import { siftClient } from '@/api/client'
import { queryKeys } from '@/api/query-keys'

/** Anonymous bootstrap call; the only request the SPA makes before a user is signed in. */
export function useAuthConfig() {
  return useQuery({
    queryKey: queryKeys.auth.config,
    queryFn: async () => {
      const { data } = await siftClient.GET('/api/v1/auth/config')
      return data
    },
    staleTime: Infinity,
  })
}
