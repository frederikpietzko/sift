import { SEVERITIES, type ReviewResultResponse } from '@sift/api-client'
import { Link } from '@tanstack/react-router'
import type { ReactNode } from 'react'

import { Markdown } from '@/components/markdown'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { countBySeverity } from '@/features/results/result-helpers'
import { SeverityBadge } from '@/features/results/severity-badge'
import { formatDateTime } from '@/lib/format'
import { cn } from '@/lib/utils'

interface ResultSummaryProps {
  result: ReviewResultResponse
}

export function ResultSummary({ result }: ResultSummaryProps) {
  const findings = result.findings ?? []
  const counts = countBySeverity(findings)
  return (
    <div className="grid gap-4 lg:grid-cols-[2fr_1fr]">
      <Card>
        <CardHeader>
          <CardTitle>Summary</CardTitle>
        </CardHeader>
        <CardContent>
          {result.summary ? (
            <Markdown>{result.summary}</Markdown>
          ) : (
            <p className="text-muted-foreground text-sm">No summary was provided.</p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Review</CardTitle>
        </CardHeader>
        <CardContent>
          <DetailList>
            <Detail label="Repository" mono>
              {result.repositoryUrl ?? '—'}
            </Detail>
            <Detail label="Branch" mono>
              {result.branch ?? '—'}
            </Detail>
            <Detail label="Base branch" mono>
              {result.baseBranch ?? '—'}
            </Detail>
            <Detail label="Commit" mono>
              {result.commitSha ?? '—'}
            </Detail>
            <Detail label="Pull request">{result.pullRequest ?? '—'}</Detail>
            <Detail label="Run">
              {result.agentRunId ? (
                <Link
                  to="/runs/$runId"
                  params={{ runId: result.agentRunId }}
                  className="font-mono text-xs underline-offset-4 hover:underline"
                >
                  {result.agentRunId}
                </Link>
              ) : (
                <span className="text-muted-foreground italic">external</span>
              )}
            </Detail>
            <Detail label="Completed">
              <Timestamp value={result.completedAt} />
            </Detail>
            <Detail label="Received">
              <Timestamp value={result.receivedAt} />
            </Detail>
            <Detail label="Findings">
              <span className="flex flex-wrap items-center gap-1.5">
                <span className="font-medium">{result.findingCount ?? findings.length}</span>
                {SEVERITIES.filter((severity) => counts[severity] > 0).map((severity) => (
                  <span key={severity} className="inline-flex items-center gap-1">
                    <SeverityBadge severity={severity} />
                    <span className="text-muted-foreground text-xs">×{counts[severity]}</span>
                  </span>
                ))}
              </span>
            </Detail>
          </DetailList>
        </CardContent>
      </Card>
    </div>
  )
}

function DetailList({ children }: { children: ReactNode }) {
  return <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">{children}</dl>
}

function Detail({ label, mono, children }: { label: string; mono?: boolean; children: ReactNode }) {
  return (
    <>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className={cn('min-w-0 break-words', mono && 'font-mono text-xs leading-5')}>
        {children}
      </dd>
    </>
  )
}

function Timestamp({ value }: { value: string | null | undefined }) {
  if (!value) return <>—</>
  return <time dateTime={value}>{formatDateTime(value)}</time>
}
