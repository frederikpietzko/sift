import type { WatchStatus } from '@/api/hooks/agents'
import { cn } from '@/lib/utils'

const LABELS: Record<WatchStatus, string> = {
  connecting: 'Connecting…',
  live: 'Live',
  reconnecting: 'Reconnecting…',
  stopped: 'Live updates off',
}

const DOT: Record<WatchStatus, string> = {
  connecting: 'bg-muted-foreground animate-pulse',
  live: 'bg-emerald-500',
  reconnecting: 'bg-amber-500 animate-pulse',
  stopped: 'bg-muted-foreground',
}

/** Small "live" pill showing the state of the SSE connection behind the runs views. */
export function WatchStatusIndicator({ status }: { status: WatchStatus }) {
  return (
    <span
      aria-live="polite"
      data-testid="watch-status"
      data-status={status}
      className="text-muted-foreground inline-flex items-center gap-2 text-xs"
    >
      <span aria-hidden className={cn('size-2 rounded-full', DOT[status])} />
      {LABELS[status]}
    </span>
  )
}
