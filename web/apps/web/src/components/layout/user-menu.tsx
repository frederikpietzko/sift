import { ChevronDownIcon, LogOutIcon, UserIcon } from 'lucide-react'

import { useMe } from '@/api/hooks/me'
import { useAuth } from '@/auth'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'

/** Shows who is signed in (from `GET /api/v1/me`) and offers logout. */
export function UserMenu() {
  const auth = useAuth()
  const me = useMe(auth.status === 'authenticated')

  // Fall back to the ID token while the profile loads so the header never flashes empty.
  const username =
    me.data?.username ?? auth.user?.profile.preferred_username ?? auth.user?.profile.sub ?? '…'
  const email = me.data?.email ?? auth.user?.profile.email ?? null

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" className="gap-2 px-2" aria-label="User menu">
          <UserIcon aria-hidden />
          <span className="max-w-40 truncate" data-testid="current-username">
            {username}
          </span>
          <ChevronDownIcon className="text-muted-foreground size-3.5" aria-hidden />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-56">
        <DropdownMenuLabel className="flex flex-col gap-0.5">
          <span className="truncate">{username}</span>
          {email && (
            <span className="text-muted-foreground truncate text-xs font-normal">{email}</span>
          )}
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => void auth.logout()}>
          <LogOutIcon aria-hidden />
          Sign out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
