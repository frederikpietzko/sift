import type { ReviewFindingResponse } from '@sift/api-client'
import { FileCodeIcon, LightbulbIcon } from 'lucide-react'

import { Markdown } from '@/components/markdown'
import { Badge } from '@/components/ui/badge'
import { groupFindingsByFile, lineRangeLabel } from '@/features/results/result-helpers'
import { SeverityBadge } from '@/features/results/severity-badge'

interface FindingListProps {
  findings: ReviewFindingResponse[]
}

/** Findings grouped by file; each group is a section headed by the file path. */
export function FindingList({ findings }: FindingListProps) {
  const groups = groupFindingsByFile(findings)
  return (
    <div className="grid gap-4">
      {groups.map((group) => (
        <section
          key={group.file}
          className="overflow-hidden rounded-lg border"
          data-testid="finding-group"
          data-file={group.file}
          aria-label={group.file}
        >
          <header className="bg-muted/50 flex flex-wrap items-center gap-2 border-b px-4 py-2">
            <FileCodeIcon aria-hidden className="text-muted-foreground size-4" />
            <h3 className="font-mono text-sm font-medium break-all">{group.file}</h3>
            <Badge variant="secondary" className="ml-auto">
              {group.findings.length} {group.findings.length === 1 ? 'finding' : 'findings'}
            </Badge>
          </header>
          <ul className="divide-y">
            {group.findings.map((finding, index) => (
              <FindingItem key={finding.id ?? index} finding={finding} />
            ))}
          </ul>
        </section>
      ))}
    </div>
  )
}

function FindingItem({ finding }: { finding: ReviewFindingResponse }) {
  const lines = lineRangeLabel(finding)
  return (
    <li className="grid gap-2 px-4 py-3" data-testid="finding" data-severity={finding.severity}>
      <div className="flex flex-wrap items-center gap-2">
        <SeverityBadge severity={finding.severity} />
        {lines && (
          <span className="text-muted-foreground font-mono text-xs" title="Line range">
            {lines}
          </span>
        )}
        {finding.category && (
          <Badge variant="outline" className="text-muted-foreground">
            {finding.category}
          </Badge>
        )}
      </div>
      <p className="text-sm whitespace-pre-wrap break-words">{finding.message ?? '—'}</p>
      {finding.suggestion && (
        <div className="bg-muted/40 flex gap-2 rounded-md border px-3 py-2">
          <LightbulbIcon aria-hidden className="text-muted-foreground mt-1 size-4 shrink-0" />
          <div className="min-w-0 flex-1">
            <p className="text-muted-foreground text-xs font-medium uppercase">Suggestion</p>
            <Markdown>{finding.suggestion}</Markdown>
          </div>
        </div>
      )}
    </li>
  )
}
