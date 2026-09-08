import { QueryClient } from '@tanstack/react-query'
import { createMemoryHistory, createRouter, RouterProvider } from '@tanstack/react-router'
import { render, type RenderOptions } from '@testing-library/react'
import type { ReactElement, ReactNode } from 'react'

import { routeTree } from '@/routeTree.gen'
import { FakeAuthClient } from '@/test/fake-auth-client'
import { TestProviders } from '@/test/providers'

export function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0, gcTime: 0 },
      mutations: { retry: false },
    },
  })
}

interface ProviderOptions {
  queryClient?: QueryClient
  authClient?: FakeAuthClient
}

/** Renders `ui` inside theme, query and auth providers (fake OIDC client, no router). */
export function renderWithProviders(
  ui: ReactElement,
  options: RenderOptions & ProviderOptions = {},
) {
  const {
    queryClient = createTestQueryClient(),
    authClient = new FakeAuthClient(),
    ...renderOptions
  } = options
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <TestProviders queryClient={queryClient} authClient={authClient}>
      {children}
    </TestProviders>
  )
  return { queryClient, authClient, ...render(ui, { wrapper: Wrapper, ...renderOptions }) }
}

/** Renders the real route tree at `initialPath` with an in-memory history. */
export function renderApp(initialPath: string, options: ProviderOptions = {}) {
  const queryClient = options.queryClient ?? createTestQueryClient()
  const authClient = options.authClient ?? new FakeAuthClient()
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [initialPath] }),
    defaultPendingMinMs: 0,
  })
  const result = render(
    <TestProviders queryClient={queryClient} authClient={authClient}>
      <RouterProvider router={router} />
    </TestProviders>,
  )
  return { ...result, router, queryClient, authClient }
}
