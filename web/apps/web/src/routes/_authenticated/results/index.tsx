import type { ReviewResultListQuery } from '@sift/api-client'
import { createFileRoute, useNavigate } from '@tanstack/react-router'

import { DEFAULT_RESULT_PAGE_SIZE, useReviewResults } from '@/api/hooks/results'
import { ApiErrorAlert } from '@/components/form/api-error-alert'
import { PageHeader } from '@/components/layout/page-header'
import { Button } from '@/components/ui/button'
import { ResultFilters, type ResultFilterValues } from '@/features/results/result-filters'
import { resultsSearchSchema } from '@/features/results/result-helpers'
import { ResultTable } from '@/features/results/result-table'
import { Pagination } from '@/features/runs/run-table'

export const Route = createFileRoute('/_authenticated/results/')({
  validateSearch: resultsSearchSchema,
  component: ResultsPage,
})

function ResultsPage() {
  const search = Route.useSearch()
  const navigate = useNavigate({ from: Route.fullPath })

  const filter: ResultFilterValues = {
    repositoryUrl: search.repositoryUrl,
    commitSha: search.commitSha,
    agentRunId: search.agentRunId,
  }
  const query: ReviewResultListQuery = {
    ...filter,
    page: search.page ?? 0,
    size: DEFAULT_RESULT_PAGE_SIZE,
  }
  const results = useReviewResults(query)

  const setFilter = (next: ResultFilterValues) =>
    navigate({ search: { ...next, page: undefined }, replace: true })
  const setPage = (page: number) =>
    navigate({ search: (previous) => ({ ...previous, page: page || undefined }) })

  return (
    <>
      <PageHeader title="Results" description="Findings produced by completed reviews." />

      <div className="grid gap-4">
        <ResultFilters
          key={`${filter.repositoryUrl ?? ''}|${filter.commitSha ?? ''}`}
          value={filter}
          onChange={setFilter}
        />

        {results.isPending && (
          <p role="status" className="text-muted-foreground text-sm">
            Loading results…
          </p>
        )}
        {results.isError && (
          <div className="grid gap-3">
            <ApiErrorAlert error={results.error} />
            <Button variant="outline" className="w-fit" onClick={() => results.refetch()}>
              Retry
            </Button>
          </div>
        )}
        {results.isSuccess &&
          (results.data.items.length === 0 ? (
            <EmptyState filtered={Object.values(filter).some(Boolean)} />
          ) : (
            <>
              <ResultTable results={results.data.items} />
              <Pagination page={results.data} onPageChange={setPage} itemLabel="results" />
            </>
          ))}
      </div>
    </>
  )
}

function EmptyState({ filtered }: { filtered: boolean }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed px-6 py-12 text-center">
      <p className="font-medium">
        {filtered ? 'No results match these filters' : 'No results yet'}
      </p>
      <p className="text-muted-foreground max-w-sm text-sm">
        {filtered
          ? 'Adjust or clear the filters to see other results.'
          : 'Results appear here once a review run has completed.'}
      </p>
    </div>
  )
}
