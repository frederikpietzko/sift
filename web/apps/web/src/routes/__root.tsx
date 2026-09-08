import { createRootRoute, Outlet } from '@tanstack/react-router'
import { lazy, Suspense } from 'react'

import { Toaster } from '@/components/ui/sonner'

const RouterDevtools = import.meta.env.DEV
  ? lazy(() =>
      import('@tanstack/react-router-devtools').then((m) => ({
        default: m.TanStackRouterDevtools,
      })),
    )
  : () => null

const QueryDevtools = import.meta.env.DEV
  ? lazy(() =>
      import('@tanstack/react-query-devtools').then((m) => ({ default: m.ReactQueryDevtools })),
    )
  : () => null

export const Route = createRootRoute({
  component: RootLayout,
})

function RootLayout() {
  return (
    <div className="min-h-full">
      <Outlet />
      <Toaster richColors closeButton />
      <Suspense fallback={null}>
        <RouterDevtools position="bottom-right" />
        <QueryDevtools buttonPosition="bottom-left" />
      </Suspense>
    </div>
  )
}
