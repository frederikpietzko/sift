import { zodResolver } from '@hookform/resolvers/zod'
import type { AgentRunResponse } from '@sift/api-client'
import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'

import { useCreateAgentRun, useUpdateAgentRun } from '@/api/hooks/agents'
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
  toUpdateAgentRunRequest,
  type StartReviewFormValues,
} from '@/features/runs/run-schema'
import { readCodeReviewSpec } from '@/features/runs/run-spec'

export type RunFormMode = 'create' | 'edit'

interface RunFormDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** `create` starts a new run, `edit` revises `run` into a successor run. */
  mode?: RunFormMode
  /** Pre-selects a repository (e.g. from the active list filter); create mode only. */
  defaultRepositoryId?: string
  /** The run being revised; required in `edit` mode. */
  run?: AgentRunResponse | null
  /** Called with the created run (202) resp. the successor run of a revision (201). */
  onSubmitted: (run: AgentRunResponse) => void
}

const EMPTY_VALUES: StartReviewFormValues = {
  repositoryId: '',
  branch: '',
  baseBranch: 'main',
  commitSha: '',
  pullRequest: '',
}

/** Prefills the form from the run's `CodeReviewRunSpec` so an edit only changes what the user types. */
function valuesOf(run: AgentRunResponse): StartReviewFormValues {
  const spec = readCodeReviewSpec(run)
  return {
    repositoryId: run.repositoryId ?? '',
    branch: spec.branch ?? '',
    baseBranch: spec.baseBranch ?? '',
    commitSha: spec.commitSha ?? '',
    pullRequest: spec.pullRequest ?? '',
  }
}

/**
 * Shared create/edit form for code review runs. Runs are immutable, so "edit" is a revision:
 * the server creates a successor run that supersedes the edited one.
 */
export function RunFormDialog({
  open,
  onOpenChange,
  mode = 'create',
  defaultRepositoryId,
  run,
  onSubmitted,
}: RunFormDialogProps) {
  const editing = mode === 'edit'
  const repositories = useRepositories()
  const create = useCreateAgentRun()
  const update = useUpdateAgentRun()
  const mutation = editing ? update : create
  const form = useForm<StartReviewFormValues>({
    resolver: zodResolver(startReviewSchema),
    defaultValues: { ...EMPTY_VALUES, repositoryId: defaultRepositoryId ?? '' },
  })

  useEffect(() => {
    if (!open) return
    form.reset(
      editing && run ? valuesOf(run) : { ...EMPTY_VALUES, repositoryId: defaultRepositoryId ?? '' },
    )
    create.reset()
    update.reset()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the dialog opens
  }, [open])

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      if (editing) {
        const id = run?.id
        if (!id) return
        const successor = await update.mutateAsync({ id, body: toUpdateAgentRunRequest(values) })
        toast.success('Review revised')
        onOpenChange(false)
        onSubmitted(successor)
      } else {
        const created = await create.mutateAsync(toCreateAgentRunRequest(values))
        toast.success('Review started')
        onOpenChange(false)
        onSubmitted(created)
      }
    } catch {
      // surfaced inline via mutation.error
    }
  })

  const repositoryList = repositories.data ?? []
  const noRepositories = repositories.isSuccess && repositoryList.length === 0
  const submitLabel = editing ? 'Save & re-run' : 'Start review'

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <form onSubmit={onSubmit} noValidate className="grid gap-4">
          <DialogHeader>
            <DialogTitle>{editing ? 'Edit review' : 'Start review'}</DialogTitle>
            <DialogDescription>
              {editing
                ? 'Runs are immutable: saving starts a new run that supersedes this one. The previous run and its result stay available.'
                : 'Runs a code review of the given commit and compares the branch against its base.'}
            </DialogDescription>
          </DialogHeader>

          <ApiErrorAlert error={mutation.error ?? repositories.error} />

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
            <Button type="submit" disabled={mutation.isPending || noRepositories}>
              {mutation.isPending ? (editing ? 'Saving…' : 'Starting…') : submitLabel}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
