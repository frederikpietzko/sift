import type { RepositoryResponse } from '@sift/api-client'
import { useEffect } from 'react'
import { toast } from 'sonner'

import { useDeleteRepository } from '@/api/hooks/repositories'
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

interface DeleteRepositoryDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  repository: RepositoryResponse | null
}

export function DeleteRepositoryDialog({
  open,
  onOpenChange,
  repository,
}: DeleteRepositoryDialogProps) {
  const remove = useDeleteRepository()

  useEffect(() => {
    if (open) remove.reset()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the dialog opens
  }, [open])

  const onConfirm = async () => {
    if (!repository?.id) return
    try {
      await remove.mutateAsync(repository.id)
      toast.success(`Repository "${repository.name ?? ''}" deleted`)
      onOpenChange(false)
    } catch {
      // surfaced inline via remove.error (e.g. 409 while runs are active)
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Delete repository?</AlertDialogTitle>
          <AlertDialogDescription>
            {repository?.name ? (
              <>
                <strong>{repository.name}</strong> and its stored token will be removed. Existing
                review results are kept. This cannot be undone.
              </>
            ) : (
              'The repository and its stored token will be removed. This cannot be undone.'
            )}
          </AlertDialogDescription>
        </AlertDialogHeader>

        <ApiErrorAlert error={remove.error} />

        <AlertDialogFooter>
          <AlertDialogCancel disabled={remove.isPending}>Cancel</AlertDialogCancel>
          <Button variant="destructive" onClick={onConfirm} disabled={remove.isPending}>
            {remove.isPending ? 'Deleting…' : 'Delete'}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
