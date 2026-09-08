import type { AgentRunResponse } from '@sift/api-client'
import type { ReactNode } from 'react'

import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { creatorLabel, readCodeReviewSpec } from '@/features/runs/run-spec'
import { formatDateTime } from '@/lib/format'
import { cn } from '@/lib/utils'

interface RunDetailsProps {
  run: AgentRunResponse
  repositoryName: string | undefined
}

export function RunDetails({ run, repositoryName }: RunDetailsProps) {
  const spec = readCodeReviewSpec(run)
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card>
        <CardHeader>
          <CardTitle>Status</CardTitle>
        </CardHeader>
        <CardContent>
          <DetailList>
            <Detail label="Reason">{run.reason ?? '—'}</Detail>
            <Detail label="Message">
              {run.message ? (
                <span className="whitespace-pre-wrap break-words">{run.message}</span>
              ) : (
                '—'
              )}
            </Detail>
            <Detail label="Source">{run.source ?? '—'}</Detail>
            <Detail label="Created by">
              <span className={run.createdBy ? undefined : 'text-muted-foreground italic'}>
                {creatorLabel(run)}
              </span>
            </Detail>
          </DetailList>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Timeline</CardTitle>
        </CardHeader>
        <CardContent>
          <DetailList>
            <Detail label="Created">
              <Timestamp value={run.createdAt} />
            </Detail>
            <Detail label="Started">
              <Timestamp value={run.startedAt} />
            </Detail>
            <Detail label="Completed">
              <Timestamp value={run.completedAt} />
            </Detail>
            <Detail label="Updated">
              <Timestamp value={run.updatedAt} />
            </Detail>
          </DetailList>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Review spec</CardTitle>
        </CardHeader>
        <CardContent>
          <DetailList>
            <Detail label="Repository">
              {repositoryName && <span className="font-medium">{repositoryName}</span>}
              {spec.repositoryUrl && (
                <span className={cn('font-mono text-xs break-all', repositoryName && 'block')}>
                  {spec.repositoryUrl}
                </span>
              )}
              {!repositoryName && !spec.repositoryUrl && '—'}
            </Detail>
            <Detail label="Branch" mono>
              {spec.branch ?? '—'}
            </Detail>
            <Detail label="Base branch" mono>
              {spec.baseBranch ?? '—'}
            </Detail>
            <Detail label="Commit" mono>
              {spec.commitSha ?? '—'}
            </Detail>
            <Detail label="Pull request">{spec.pullRequest ?? '—'}</Detail>
          </DetailList>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Kubernetes</CardTitle>
        </CardHeader>
        <CardContent>
          <DetailList>
            <Detail label="Custom resource" mono>
              {run.crName ?? '—'}
            </Detail>
            <Detail label="Resource UID" mono>
              {run.crUid ?? '—'}
            </Detail>
            <Detail label="Generation" mono>
              {run.generation ?? '—'}
            </Detail>
            <Detail label="Execution" mono>
              {run.executionId ?? '—'}
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
