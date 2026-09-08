import type { AgentPhase, AgentRunResponse } from '@sift/api-client'
import { Loader2Icon } from 'lucide-react'

import { Badge } from '@/components/ui/badge'
import { phaseLabel } from '@/features/runs/run-spec'
import { cn } from '@/lib/utils'

const PHASE_STYLES: Record<AgentPhase, string> = {
  CREATED: 'border-transparent bg-muted text-muted-foreground',
  PENDING: 'border-transparent bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-200',
  RUNNING: 'border-transparent bg-blue-100 text-blue-900 dark:bg-blue-950 dark:text-blue-200',
  SUCCESS:
    'border-transparent bg-emerald-100 text-emerald-900 dark:bg-emerald-950 dark:text-emerald-200',
  FAILED: 'border-transparent bg-red-100 text-red-900 dark:bg-red-950 dark:text-red-200',
  CANCELLED: 'border-transparent bg-muted text-muted-foreground line-through',
}

interface PhaseBadgeProps {
  phase: AgentRunResponse['phase']
  className?: string
}

export function PhaseBadge({ phase, className }: PhaseBadgeProps) {
  return (
    <Badge
      variant="outline"
      className={cn(phase ? PHASE_STYLES[phase] : undefined, className)}
      data-phase={phase}
    >
      {phase === 'RUNNING' && <Loader2Icon aria-hidden className="animate-spin" />}
      {phaseLabel(phase)}
    </Badge>
  )
}
