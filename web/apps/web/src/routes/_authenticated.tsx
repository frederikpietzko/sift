import { createFileRoute, Outlet } from '@tanstack/react-router'

import { RequireAuth } from '@/auth'
import { AppShell } from '@/components/layout/app-shell'

/** Pathless layout: everything below requires a session and renders inside the shell. */
export const Route = createFileRoute('/_authenticated')({
  component: AuthenticatedLayout,
})

function AuthenticatedLayout() {
  return (
    <RequireAuth>
      <AppShell>
        <Outlet />
      </AppShell>
    </RequireAuth>
  )
}
