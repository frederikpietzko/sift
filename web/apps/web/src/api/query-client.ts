import { isSiftApiError } from '@sift/api-client'
import { QueryClient } from '@tanstack/react-query'

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 10_000,
        refetchOnWindowFocus: false,
        retry: (failureCount, error) => {
          // client errors (4xx) are not transient; don't hammer the server
          if (isSiftApiError(error) && error.status < 500) return false
          return failureCount < 2
        },
      },
    },
  })
}
