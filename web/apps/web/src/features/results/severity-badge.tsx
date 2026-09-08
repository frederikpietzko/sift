import type { Severity } from '@sift/api-client'

import { Badge } from '@/components/ui/badge'
import { severityLabel } from '@/features/results/result-helpers'
import { cn } from '@/lib/utils'

const SEVERITY_STYLES: Record<Severity, string> = {
  BLOCKER: 'border-transparent bg-red-100 text-red-900 dark:bg-red-950 dark:text-red-200',
  MAJOR: 'border-transparent bg-orange-100 text-orange-900 dark:bg-orange-950 dark:text-orange-200',
  MINOR: 'border-transparent bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-200',
  INFO: 'border-transparent bg-sky-100 text-sky-900 dark:bg-sky-950 dark:text-sky-200',
}

interface SeverityBadgeProps {
  severity: Severity | undefined
  className?: string
}

export function SeverityBadge({ severity, className }: SeverityBadgeProps) {
  return (
    <Badge
      variant="outline"
      className={cn(severity ? SEVERITY_STYLES[severity] : undefined, className)}
      data-severity={severity}
    >
      {severityLabel(severity)}
    </Badge>
  )
}
