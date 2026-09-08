import type { RepositoryResponse } from '@sift/api-client'
import { createFileRoute } from '@tanstack/react-router'
import { PlusIcon } from 'lucide-react'
import { useState } from 'react'

import { useRepositories } from '@/api/hooks/repositories'
import { ApiErrorAlert } from '@/components/form/api-error-alert'
import { PageHeader } from '@/components/layout/page-header'
import { Button } from '@/components/ui/button'
import { DeleteRepositoryDialog } from '@/features/repositories/delete-repository-dialog'
import {
  CreateRepositoryDialog,
  EditRepositoryDialog,
} from '@/features/repositories/repository-form-dialog'
import { RepositoryTable } from '@/features/repositories/repository-table'

export const Route = createFileRoute('/_authenticated/repositories/')({
  component: RepositoriesPage,
})

type DialogState =
  | { kind: 'closed' }
  | { kind: 'create' }
  | { kind: 'edit'; repository: RepositoryResponse }
  | { kind: 'delete'; repository: RepositoryResponse }

function RepositoriesPage() {
  const repositories = useRepositories()
  const [dialog, setDialog] = useState<DialogState>({ kind: 'closed' })
  const close = () => setDialog({ kind: 'closed' })
  // Keep the last selected repository so dialogs don't flash empty while closing
  const [selected, setSelected] = useState<RepositoryResponse | null>(null)
  const select = (next: DialogState) => {
    if (next.kind === 'edit' || next.kind === 'delete') setSelected(next.repository)
    setDialog(next)
  }

  return (
    <>
      <PageHeader
        title="Repositories"
        description="Git repositories that reviews can be started for."
        actions={
          <Button onClick={() => setDialog({ kind: 'create' })}>
            <PlusIcon />
            Register repository
          </Button>
        }
      />

      {repositories.isPending && (
        <p role="status" className="text-muted-foreground text-sm">
          Loading repositories…
        </p>
      )}
      {repositories.isError && (
        <div className="grid gap-3">
          <ApiErrorAlert error={repositories.error} />
          <Button variant="outline" className="w-fit" onClick={() => repositories.refetch()}>
            Retry
          </Button>
        </div>
      )}
      {repositories.isSuccess &&
        (repositories.data.length === 0 ? (
          <EmptyState onCreate={() => setDialog({ kind: 'create' })} />
        ) : (
          <RepositoryTable
            repositories={repositories.data}
            onEdit={(repository) => select({ kind: 'edit', repository })}
            onDelete={(repository) => select({ kind: 'delete', repository })}
          />
        ))}

      <CreateRepositoryDialog
        open={dialog.kind === 'create'}
        onOpenChange={(open) => (open ? setDialog({ kind: 'create' }) : close())}
      />
      <EditRepositoryDialog
        open={dialog.kind === 'edit'}
        onOpenChange={(open) => !open && close()}
        repository={selected}
      />
      <DeleteRepositoryDialog
        open={dialog.kind === 'delete'}
        onOpenChange={(open) => !open && close()}
        repository={selected}
      />
    </>
  )
}

function EmptyState({ onCreate }: { onCreate: () => void }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed px-6 py-12 text-center">
      <p className="font-medium">No repositories yet</p>
      <p className="text-muted-foreground max-w-sm text-sm">
        Register a Git repository to start reviewing its branches and pull requests.
      </p>
      <Button variant="outline" onClick={onCreate}>
        <PlusIcon />
        Register repository
      </Button>
    </div>
  )
}
