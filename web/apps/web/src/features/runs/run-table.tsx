import type { AgentRunResponse, PageResponseAgentRunResponse } from '@sift/api-client'
import { Link } from '@tanstack/react-router'
import { ChevronLeftIcon, ChevronRightIcon, Trash2Icon } from 'lucide-react'
import { useState } from 'react'

import { Button } from '@/components/ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { DeleteRunDialog, runLabel } from '@/features/runs/delete-run-dialog'
import { PhaseBadge } from '@/features/runs/phase-badge'
import { creatorLabel, kindLabel, readCodeReviewSpec, shortSha } from '@/features/runs/run-spec'
import { formatDateTime } from '@/lib/format'

interface RunTableProps {
  runs: AgentRunResponse[]
  /** Maps `repositoryId` to a display name; falls back to the URL from the run spec. */
  repositoryNames?: ReadonlyMap<string, string>
}

export function RunTable({ runs, repositoryNames }: RunTableProps) {
  const [pendingDelete, setPendingDelete] = useState<AgentRunResponse | null>(null)

  return (
    <div className="overflow-hidden rounded-lg border">
      <Table aria-label="Runs">
        <TableHeader>
          <TableRow>
            <TableHead>Phase</TableHead>
            <TableHead>Kind</TableHead>
            <TableHead>Repository</TableHead>
            <TableHead>Branch</TableHead>
            <TableHead>Commit</TableHead>
            <TableHead>Created by</TableHead>
            <TableHead>Updated</TableHead>
            <TableHead className="w-0">
              <span className="sr-only">Actions</span>
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {runs.map((run) => {
            const spec = readCodeReviewSpec(run)
            const repository =
              (run.repositoryId ? repositoryNames?.get(run.repositoryId) : undefined) ??
              spec.repositoryUrl ??
              '—'
            const id = run.id ?? run.crName ?? ''
            return (
              <TableRow key={id} data-testid="run-row" data-run-id={run.id}>
                <TableCell>
                  <PhaseBadge phase={run.phase} />
                </TableCell>
                <TableCell>
                  {run.id ? (
                    <Link
                      to="/runs/$runId"
                      params={{ runId: run.id }}
                      className="font-medium underline-offset-4 hover:underline"
                    >
                      {kindLabel(run.kind)}
                    </Link>
                  ) : (
                    kindLabel(run.kind)
                  )}
                </TableCell>
                <TableCell className="text-muted-foreground max-w-[18rem] truncate">
                  {repository}
                </TableCell>
                <TableCell className="font-mono text-xs">
                  {spec.branch ?? '—'}
                  {spec.baseBranch && (
                    <span className="text-muted-foreground"> → {spec.baseBranch}</span>
                  )}
                </TableCell>
                <TableCell className="font-mono text-xs" title={spec.commitSha}>
                  {shortSha(spec.commitSha)}
                </TableCell>
                <TableCell className={run.createdBy ? undefined : 'text-muted-foreground italic'}>
                  {creatorLabel(run)}
                </TableCell>
                <TableCell className="text-muted-foreground whitespace-nowrap">
                  <time dateTime={run.updatedAt ?? undefined}>{formatDateTime(run.updatedAt)}</time>
                </TableCell>
                <TableCell className="text-right">
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label={`Delete run ${runLabel(run)}`}
                    disabled={!run.id}
                    onClick={() => setPendingDelete(run)}
                  >
                    <Trash2Icon className="text-destructive" />
                  </Button>
                </TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
      <DeleteRunDialog
        open={pendingDelete !== null}
        onOpenChange={(open) => !open && setPendingDelete(null)}
        run={pendingDelete}
      />
    </div>
  )
}

interface PaginationProps {
  page: Pick<PageResponseAgentRunResponse, 'page' | 'size' | 'total'>
  onPageChange: (page: number) => void
  /** Plural noun used in the summary text ("No runs"). */
  itemLabel?: string
}

export function Pagination({ page, onPageChange, itemLabel = 'runs' }: PaginationProps) {
  const current = page.page ?? 0
  const size = page.size ?? 1
  const total = page.total ?? 0
  const pageCount = Math.max(1, Math.ceil(total / Math.max(1, size)))
  const from = total === 0 ? 0 : current * size + 1
  const to = Math.min(total, (current + 1) * size)

  return (
    <nav className="flex items-center justify-between gap-4" aria-label="Pagination">
      <p className="text-muted-foreground text-sm">
        {total === 0 ? `No ${itemLabel}` : `Showing ${from}–${to} of ${total}`}
      </p>
      <div className="flex items-center gap-2">
        <span className="text-muted-foreground text-sm">
          Page {current + 1} of {pageCount}
        </span>
        <Button
          variant="outline"
          size="icon"
          aria-label="Previous page"
          disabled={current <= 0}
          onClick={() => onPageChange(current - 1)}
        >
          <ChevronLeftIcon />
        </Button>
        <Button
          variant="outline"
          size="icon"
          aria-label="Next page"
          disabled={current + 1 >= pageCount}
          onClick={() => onPageChange(current + 1)}
        >
          <ChevronRightIcon />
        </Button>
      </div>
    </nav>
  )
}
