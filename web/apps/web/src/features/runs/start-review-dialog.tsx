import { zodResolver } from '@hookform/resolvers/zod'
import type { AgentRunResponse } from '@sift/api-client'
import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'

import { useCreateAgentRun } from '@/api/hooks/agents'
import { useRepositories } from '@/api/hooks/repositories'
import { ApiErrorAlert } from '@/components/form/api-error-alert'
import { FormField } from '@/components/form/form-field'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { NativeSelect } from '@/components/ui/native-select'
import {
  startReviewSchema,
  toCreateAgentRunRequest,
  type StartReviewFormValues,
} from '@/features/runs/run-schema'

interface StartReviewDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Pre-selects a repository (e.g. from the active list filter). */
  defaultRepositoryId?: string
  /** Called after the server accepted the run (202). */
  onCreated: (run: AgentRunResponse) => void
}

const EMPTY_VALUES: StartReviewFormValues = {
  repositoryId: '',
  branch: '',
  baseBranch: 'main',
  commitSha: '',
  pullRequest: '',
}

export function StartReviewDialog({
  open,
  onOpenChange,
  defaultRepositoryId,
  onCreated,
}: StartReviewDialogProps) {
  const repositories = useRepositories()
  const create = useCreateAgentRun()
  const form = useForm<StartReviewFormValues>({
    resolver: zodResolver(startReviewSchema),
    defaultValues: { ...EMPTY_VALUES, repositoryId: defaultRepositoryId ?? '' },
  })

  useEffect(() => {
    if (!open) return
    form.reset({ ...EMPTY_VALUES, repositoryId: defaultRepositoryId ?? '' })
    create.reset()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the dialog opens
  }, [open])

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      const run = await create.mutateAsync(toCreateAgentRunRequest(values))
      toast.success('Review started')
      onOpenChange(false)
      onCreated(run)
    } catch {
      // surfaced inline via create.error
    }
  })

  const repositoryList = repositories.data ?? []
  const noRepositories = repositories.isSuccess && repositoryList.length === 0

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <form onSubmit={onSubmit} noValidate className="grid gap-4">
          <DialogHeader>
            <DialogTitle>Start review</DialogTitle>
            <DialogDescription>
              Runs a code review of the given commit and compares the branch against its base.
            </DialogDescription>
          </DialogHeader>

          <ApiErrorAlert error={create.error ?? repositories.error} />

          <FormField
            label="Repository"
            error={form.formState.errors.repositoryId?.message}
            description={noRepositories ? 'Register a repository first.' : undefined}
          >
            {(control) => (
              <NativeSelect
                {...control}
                {...form.register('repositoryId')}
                disabled={repositories.isPending || noRepositories}
                autoFocus
              >
                <option value="">
                  {repositories.isPending ? 'Loading repositories…' : 'Select a repository'}
                </option>
                {repositoryList.map((repository) => (
                  <option key={repository.id} value={repository.id}>
                    {repository.name ?? repository.url ?? repository.id}
                  </option>
                ))}
              </NativeSelect>
            )}
          </FormField>

          <div className="grid gap-4 sm:grid-cols-2">
            <FormField label="Branch" error={form.formState.errors.branch?.message}>
              {(control) => (
                <Input
                  {...control}
                  {...form.register('branch')}
                  placeholder="feature/my-change"
                  autoComplete="off"
                  spellCheck={false}
                />
              )}
            </FormField>
            <FormField label="Base branch" error={form.formState.errors.baseBranch?.message}>
              {(control) => (
                <Input
                  {...control}
                  {...form.register('baseBranch')}
                  placeholder="main"
                  autoComplete="off"
                  spellCheck={false}
                />
              )}
            </FormField>
          </div>

          <FormField
            label="Commit SHA"
            description="Full 40-character SHA of the commit to review."
            error={form.formState.errors.commitSha?.message}
          >
            {(control) => (
              <Input
                {...control}
                {...form.register('commitSha')}
                className="font-mono"
                placeholder="0123456789abcdef0123456789abcdef01234567"
                autoComplete="off"
                spellCheck={false}
                maxLength={40}
              />
            )}
          </FormField>

          <FormField
            label="Pull request"
            optional
            description="Number or URL; enables posting the review as PR comments."
            error={form.formState.errors.pullRequest?.message}
          >
            {(control) => (
              <Input
                {...control}
                {...form.register('pullRequest')}
                placeholder="42"
                autoComplete="off"
              />
            )}
          </FormField>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={create.isPending || noRepositories}>
              {create.isPending ? 'Starting…' : 'Start review'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
