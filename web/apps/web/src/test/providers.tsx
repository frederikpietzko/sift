import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import type { ReactNode } from 'react'

import { AuthProvider } from '@/auth'
import { ThemeProvider } from '@/components/theme/theme-provider'
import { fakeAuthClientFactory, type FakeAuthClient } from '@/test/fake-auth-client'

export interface TestProvidersProps {
  queryClient: QueryClient
  authClient: FakeAuthClient
  children: ReactNode
}

export function TestProviders({ children, queryClient, authClient }: TestProvidersProps) {
  return (
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <AuthProvider createClient={fakeAuthClientFactory(authClient)}>{children}</AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>
  )
}
