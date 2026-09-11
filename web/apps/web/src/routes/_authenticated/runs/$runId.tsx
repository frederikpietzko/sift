import { isSiftApiError, isTerminalPhase, type AgentRunResponse } from '@sift/api-client'
import { createFileRoute, Link, useNavigate } from '@tanstack/react-router'
import { ArrowLeftIcon, FileSearchIcon, PencilIcon, Trash2Icon } from 'lucide-react'
import { useState } from 'react'

import { useAgentRun, useAgentRunWatch } from '@/api/hooks/agents'
import { useRepository } from '@/api/hooks/repositories'
import { useReviewResultForRun } from '@/api/hooks/results'
import { ApiErrorAlert } from '@/components/form/api-error-alert'
import { PageHeader } from '@/components/layout/page-header'
import { Button } from '@/components/ui/button'
import { CancelRunButton } from '@/features/runs/cancel-run-button'
import { DeleteRunDialog } from '@/features/runs/delete-run-dialog'
import { PhaseBadge } from '@/features/runs/phase-badge'
import { RunDetails } from '@/features/runs/run-details'
import { RunFormDialog } from '@/features/runs/run-form-dialog'
import { kindLabel } from '@/features/runs/run-spec'
import { WatchStatusIndicator } from '@/features/runs/watch-status'

export const Route = createFileRoute('/_authenticated/runs/$runId')({
  component: RunDetailPage,
})

function RunDetailPage() {
  const { runId } = Route.useParams()
  const run = useAgentRun(runId)
  const terminal = isTerminalPhase(run.data?.phase)
  // the stream narrows to this run and stops once it can no longer change
  const watch = useAgentRunWatch({ query: { agentId: runId }, enabled: !terminal })
  const repository = useRepository(run.data?.repositoryId ?? '', Boolean(run.data?.repositoryId))
  // a stored result outlives its custom resource, so superseded/cancelled runs can have one too
  const result = useReviewResultForRun(runId, run.isSuccess)

  return (
    <>
      <Button variant="ghost" size="sm" className="mb-4 -ml-2" asChild>
        <Link to="/runs">
          <ArrowLeftIcon />
          All runs
        </Link>
      </Button>

      {run.isPending && (
        <p role="status" className="text-muted-foreground text-sm">
          Loading run…
        </p>
      )}
      {run.isError && (
        <div className="grid gap-3">
          <ApiErrorAlert error={run.error} />
          {isSiftApiError(run.error) && run.error.status === 404 ? (
            <Button variant="outline" className="w-fit" asChild>
              <Link to="/runs">Back to runs</Link>
            </Button>
          ) : (
            <Button variant="outline" className="w-fit" onClick={() => run.refetch()}>
              Retry
            </Button>
          )}
        </div>
      )}
      {run.isSuccess && (
        <RunDetailContent
          run={run.data}
          repositoryName={repository.data?.name}
          resultId={result.result?.id}
          watchStatus={watch.status}
        />
      )}
    </>
  )
}

interface RunDetailContentProps {
  run: AgentRunResponse
  repositoryName: string | undefined
  resultId: string | undefined
  watchStatus: ReturnType<typeof useAgentRunWatch>['status']
}

function RunDetailContent({ run, repositoryName, resultId, watchStatus }: RunDetailContentProps) {
  const terminal = isTerminalPhase(run.phase)
  const navigate = useNavigate()
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  // `EXTERNAL` runs are not owned by the API and cannot be revised
  const editable = run.source !== 'EXTERNAL'
  return (
    <>
      <PageHeader
        title={kindLabel(run.kind)}
        description={run.id}
        actions={
          <>
            <PhaseBadge phase={run.phase} className="text-sm" />
            {!terminal && <WatchStatusIndicator status={watchStatus} />}
            {resultId && (
              <Button variant="outline" asChild>
                <Link to="/results/$resultId" params={{ resultId }}>
                  <FileSearchIcon />
                  View result
                </Link>
              </Button>
            )}
            <Button
              variant="outline"
              disabled={!editable}
              title={editable ? undefined : 'External runs are not managed through the API'}
              onClick={() => setEditOpen(true)}
            >
              <PencilIcon />
              Edit run
            </Button>
            <CancelRunButton run={run} />
            <Button variant="destructive" onClick={() => setDeleteOpen(true)}>
              <Trash2Icon />
              Delete run
            </Button>
          </>
        }
      />
      <RunDetails run={run} repositoryName={repositoryName} />
      <DeleteRunDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        run={run}
        onDeleted={() => void navigate({ to: '/runs' })}
      />
      <RunFormDialog
        open={editOpen}
        onOpenChange={setEditOpen}
        mode="edit"
        run={run}
        onSubmitted={(successor) => {
          if (successor?.id) void navigate({ to: '/runs/$runId', params: { runId: successor.id } })
        }}
      />
    </>
  )
}
