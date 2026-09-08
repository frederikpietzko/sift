import { isTerminalPhase, type AgentRunResponse } from '@sift/api-client'
import { useEffect } from 'react'
import { toast } from 'sonner'

import { useDeleteAgentRun } from '@/api/hooks/agents'
import { ApiErrorAlert } from '@/components/form/api-error-alert'
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { kindLabel, readCodeReviewSpec, shortSha } from '@/features/runs/run-spec'

interface DeleteRunDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  run: AgentRunResponse | null
  /** Called after the run was deleted (e.g. to leave the detail page). */
  onDeleted?: () => void
}

/** Names the run, warns that an active run is cancelled and that the result is gone for good. */
export function DeleteRunDialog({ open, onOpenChange, run, onDeleted }: DeleteRunDialogProps) {
  const remove = useDeleteAgentRun()

  useEffect(() => {
    if (open) remove.reset()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the dialog opens
  }, [open])

  const active = run ? !isTerminalPhase(run.phase) : false

  const onConfirm = async () => {
    if (!run?.id) return
    try {
      await remove.mutateAsync(run.id)
      toast.success(`Run ${runLabel(run)} deleted`)
      onOpenChange(false)
      onDeleted?.()
    } catch {
      // surfaced inline via remove.error
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Delete run?</AlertDialogTitle>
          <AlertDialogDescription>
            {run ? (
              <>
                <strong>{runLabel(run)}</strong> will be removed.{' '}
                {active && 'This run is still active and will be cancelled first. '}
                Its review result and findings are deleted permanently. This cannot be undone.
              </>
            ) : (
              'The run, its review result and its findings are deleted permanently. This cannot be undone.'
            )}
          </AlertDialogDescription>
        </AlertDialogHeader>

        <ApiErrorAlert error={remove.error} />

        <AlertDialogFooter>
          <AlertDialogCancel disabled={remove.isPending}>Keep run</AlertDialogCancel>
          <Button variant="destructive" onClick={onConfirm} disabled={remove.isPending}>
            {remove.isPending ? 'Deleting…' : 'Delete run'}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

/** Short human-readable identity of a run: kind plus branch or commit when available. */
export function runLabel(run: AgentRunResponse): string {
  const spec = readCodeReviewSpec(run)
  const detail = spec.branch ?? (spec.commitSha ? shortSha(spec.commitSha) : run.id)
  const kind = kindLabel(run.kind)
  return detail ? `${kind} · ${detail}` : kind
}
