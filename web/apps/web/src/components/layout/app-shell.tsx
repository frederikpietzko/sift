import { Link, type LinkProps } from '@tanstack/react-router'
import { FolderGitIcon, ListChecksIcon, PlayIcon, ShieldCheckIcon } from 'lucide-react'
import type { ReactNode } from 'react'

import { UserMenu } from '@/components/layout/user-menu'
import { ThemeToggle } from '@/components/theme/theme-toggle'

interface NavItem {
  to: LinkProps['to']
  label: string
  icon: typeof PlayIcon
}

const NAV_ITEMS: readonly NavItem[] = [
  { to: '/runs', label: 'Runs', icon: PlayIcon },
  { to: '/repositories', label: 'Repositories', icon: FolderGitIcon },
  { to: '/results', label: 'Results', icon: ListChecksIcon },
]

/** Authenticated layout: sidebar navigation (collapses to icons on narrow screens) + header. */
export function AppShell({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-full">
      <aside className="bg-sidebar text-sidebar-foreground border-sidebar-border flex w-14 shrink-0 flex-col border-r md:w-56">
        <Link
          to="/"
          className="flex h-14 items-center gap-2 px-4 font-semibold tracking-tight"
          aria-label="Sift home"
        >
          <ShieldCheckIcon className="text-sidebar-primary size-6 shrink-0" aria-hidden />
          <span className="hidden md:inline">Sift</span>
        </Link>
        <nav aria-label="Main" className="flex flex-1 flex-col gap-1 px-2 py-2">
          {NAV_ITEMS.map(({ to, label, icon: Icon }) => (
            <Link
              key={to}
              to={to}
              title={label}
              className="hover:bg-sidebar-accent hover:text-sidebar-accent-foreground flex h-9 items-center gap-3 rounded-md px-3 text-sm font-medium"
              activeProps={{
                className: 'bg-sidebar-accent text-sidebar-accent-foreground',
                'aria-current': 'page',
              }}
            >
              <Icon className="size-4 shrink-0" aria-hidden />
              <span className="hidden md:inline">{label}</span>
            </Link>
          ))}
        </nav>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="bg-background/95 supports-[backdrop-filter]:bg-background/60 sticky top-0 z-10 flex h-14 items-center justify-end gap-2 border-b px-4 backdrop-blur">
          <ThemeToggle />
          <UserMenu />
        </header>
        <main className="flex-1 p-4 md:p-6">{children}</main>
      </div>
    </div>
  )
}
