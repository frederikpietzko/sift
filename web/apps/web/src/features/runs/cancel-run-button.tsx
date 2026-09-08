import { isTerminalPhase, type AgentRunResponse } from '@sift/api-client'
import { BanIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { useCancelAgentRun } from '@/api/hooks/agents'
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
import { phaseLabel } from '@/features/runs/run-spec'

interface CancelRunButtonProps {
  run: AgentRunResponse
}

/** Requests cancellation; disabled once the run reached a terminal phase (nothing left to stop). */
export function CancelRunButton({ run }: CancelRunButtonProps) {
  const [open, setOpen] = useState(false)
  const cancel = useCancelAgentRun()
  const terminal = isTerminalPhase(run.phase)
  const disabled = terminal || !run.id

  const confirm = async () => {
    if (!run.id) return
    try {
      await cancel.mutateAsync(run.id)
      toast.success('Cancellation requested')
      setOpen(false)
    } catch {
      // surfaced inline via cancel.error
    }
  }

  return (
    <>
      <Button
        variant="destructive"
        disabled={disabled}
        title={terminal ? `Run already ${phaseLabel(run.phase).toLowerCase()}` : undefined}
        onClick={() => {
          cancel.reset()
          setOpen(true)
        }}
      >
        <BanIcon />
        Cancel run
      </Button>
      <AlertDialog open={open} onOpenChange={setOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Cancel this run?</AlertDialogTitle>
            <AlertDialogDescription>
              The agent job is stopped and the run is marked as cancelled. Findings collected so far
              are discarded.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <ApiErrorAlert error={cancel.error} />
          <AlertDialogFooter>
            <AlertDialogCancel disabled={cancel.isPending}>Keep running</AlertDialogCancel>
            <Button variant="destructive" onClick={confirm} disabled={cancel.isPending}>
              {cancel.isPending ? 'Cancelling…' : 'Cancel run'}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
