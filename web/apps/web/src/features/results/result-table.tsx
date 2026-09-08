import type { ReviewResultSummaryResponse } from '@sift/api-client'
import { Link } from '@tanstack/react-router'

import { Badge } from '@/components/ui/badge'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { repositoryLabel } from '@/features/results/result-helpers'
import { shortSha } from '@/features/runs/run-spec'
import { formatDateTime } from '@/lib/format'

interface ResultTableProps {
  results: ReviewResultSummaryResponse[]
}

export function ResultTable({ results }: ResultTableProps) {
  return (
    <div className="overflow-hidden rounded-lg border">
      <Table aria-label="Review results">
        <TableHeader>
          <TableRow>
            <TableHead>Repository</TableHead>
            <TableHead>Branch</TableHead>
            <TableHead>Commit</TableHead>
            <TableHead>PR</TableHead>
            <TableHead className="text-right">Findings</TableHead>
            <TableHead>Run</TableHead>
            <TableHead>Received</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {results.map((result) => {
            const id = result.id ?? result.executionId ?? ''
            return (
              <TableRow key={id} data-testid="result-row" data-result-id={result.id}>
                <TableCell
                  className="max-w-[18rem] truncate font-medium"
                  title={result.repositoryUrl}
                >
                  {result.id ? (
                    <Link
                      to="/results/$resultId"
                      params={{ resultId: result.id }}
                      className="underline-offset-4 hover:underline"
                    >
                      {repositoryLabel(result.repositoryUrl)}
                    </Link>
                  ) : (
                    repositoryLabel(result.repositoryUrl)
                  )}
                </TableCell>
                <TableCell className="font-mono text-xs">
                  {result.branch ?? '—'}
                  {result.baseBranch && (
                    <span className="text-muted-foreground"> → {result.baseBranch}</span>
                  )}
                </TableCell>
                <TableCell className="font-mono text-xs" title={result.commitSha}>
                  {shortSha(result.commitSha)}
                </TableCell>
                <TableCell className="text-muted-foreground">{result.pullRequest ?? '—'}</TableCell>
                <TableCell className="text-right">
                  <Badge variant={result.findingCount ? 'secondary' : 'outline'}>
                    {result.findingCount ?? 0}
                  </Badge>
                </TableCell>
                <TableCell>
                  {result.agentRunId ? (
                    <Link
                      to="/runs/$runId"
                      params={{ runId: result.agentRunId }}
                      className="font-mono text-xs underline-offset-4 hover:underline"
                      title={result.agentRunId}
                    >
                      {result.agentRunId.slice(0, 8)}
                    </Link>
                  ) : (
                    <span className="text-muted-foreground italic">external</span>
                  )}
                </TableCell>
                <TableCell className="text-muted-foreground whitespace-nowrap">
                  <time dateTime={result.receivedAt ?? undefined}>
                    {formatDateTime(result.receivedAt)}
                  </time>
                </TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}
