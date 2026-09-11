import type { AgentRunListQuery } from '@sift/api-client'
import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { PlayIcon } from 'lucide-react'
import { useMemo, useState } from 'react'

import { DEFAULT_PAGE_SIZE, useAgentRuns, useAgentRunWatch } from '@/api/hooks/agents'
import { useRepositories } from '@/api/hooks/repositories'
import { ApiErrorAlert } from '@/components/form/api-error-alert'
import { PageHeader } from '@/components/layout/page-header'
import { Button } from '@/components/ui/button'
import { RunFilters, type RunFilterValues } from '@/features/runs/run-filters'
import { RunFormDialog } from '@/features/runs/run-form-dialog'
import { runsSearchSchema } from '@/features/runs/run-schema'
import { Pagination, RunTable } from '@/features/runs/run-table'
import { WatchStatusIndicator } from '@/features/runs/watch-status'

export const Route = createFileRoute('/_authenticated/runs/')({
  validateSearch: runsSearchSchema,
  component: RunsPage,
})

function RunsPage() {
  const search = Route.useSearch()
  const navigate = useNavigate({ from: Route.fullPath })
  const [startOpen, setStartOpen] = useState(false)

  const filter: RunFilterValues = {
    kind: search.kind,
    phase: search.phase,
    repositoryId: search.repositoryId,
    mine: search.mine,
  }
  const query: AgentRunListQuery = {
    ...filter,
    page: search.page ?? 0,
    size: DEFAULT_PAGE_SIZE,
  }

  const runs = useAgentRuns(query)
  const repositories = useRepositories()
  const watch = useAgentRunWatch({ query: { kind: filter.kind, mine: filter.mine } })

  const repositoryNames = useMemo(() => {
    const names = new Map<string, string>()
    for (const repository of repositories.data ?? []) {
      if (repository.id)
        names.set(repository.id, repository.name ?? repository.url ?? repository.id)
    }
    return names
  }, [repositories.data])

  const setFilter = (next: RunFilterValues) =>
    navigate({ search: { ...next, page: undefined }, replace: true })
  const setPage = (page: number) =>
    navigate({ search: (previous) => ({ ...previous, page: page || undefined }) })

  return (
    <>
      <PageHeader
        title="Runs"
        description="Agent runs and their live progress."
        actions={
          <>
            <WatchStatusIndicator status={watch.status} />
            <Button onClick={() => setStartOpen(true)}>
              <PlayIcon />
              Start review
            </Button>
          </>
        }
      />

      <div className="grid gap-4">
        <RunFilters value={filter} repositories={repositories.data ?? []} onChange={setFilter} />

        {runs.isPending && (
          <p role="status" className="text-muted-foreground text-sm">
            Loading runs…
          </p>
        )}
        {runs.isError && (
          <div className="grid gap-3">
            <ApiErrorAlert error={runs.error} />
            <Button variant="outline" className="w-fit" onClick={() => runs.refetch()}>
              Retry
            </Button>
          </div>
        )}
        {runs.isSuccess &&
          (runs.data.items.length === 0 ? (
            <EmptyState filtered={Object.values(filter).some(Boolean)} />
          ) : (
            <>
              <RunTable runs={runs.data.items} repositoryNames={repositoryNames} />
              <Pagination page={runs.data} onPageChange={setPage} />
            </>
          ))}
      </div>

      <RunFormDialog
        open={startOpen}
        onOpenChange={setStartOpen}
        defaultRepositoryId={filter.repositoryId}
        onSubmitted={(run) => {
          if (run?.id) void navigate({ to: '/runs/$runId', params: { runId: run.id } })
        }}
      />
    </>
  )
}

function EmptyState({ filtered }: { filtered: boolean }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed px-6 py-12 text-center">
      <p className="font-medium">{filtered ? 'No runs match these filters' : 'No runs yet'}</p>
      <p className="text-muted-foreground max-w-sm text-sm">
        {filtered
          ? 'Adjust or clear the filters to see other runs.'
          : 'Start a review to see its progress here in real time.'}
      </p>
    </div>
  )
}
