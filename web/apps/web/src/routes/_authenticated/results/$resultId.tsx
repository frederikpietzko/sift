import { isSiftApiError, type ReviewResultResponse } from '@sift/api-client'
import { createFileRoute, Link, useNavigate } from '@tanstack/react-router'
import { ArrowLeftIcon, ActivityIcon } from 'lucide-react'
import { useMemo } from 'react'

import { useReviewFindings, useReviewResult } from '@/api/hooks/results'
import { ApiErrorAlert } from '@/components/form/api-error-alert'
import { PageHeader } from '@/components/layout/page-header'
import { Button } from '@/components/ui/button'
import { FindingFilters } from '@/features/results/finding-filters'
import { FindingList } from '@/features/results/finding-list'
import {
  countBySeverity,
  distinctFiles,
  repositoryLabel,
  resultDetailSearchSchema,
  type ResultDetailSearch,
} from '@/features/results/result-helpers'
import { ResultSummary } from '@/features/results/result-summary'
import { shortSha } from '@/features/runs/run-spec'

export const Route = createFileRoute('/_authenticated/results/$resultId')({
  validateSearch: resultDetailSearchSchema,
  component: ResultDetailPage,
})

function ResultDetailPage() {
  const { resultId } = Route.useParams()
  const search = Route.useSearch()
  const navigate = useNavigate({ from: Route.fullPath })
  const result = useReviewResult(resultId)

  const filter: ResultDetailSearch = { severity: search.severity, file: search.file }
  const setFilter = (next: ResultDetailSearch) => navigate({ search: next, replace: true })

  return (
    <>
      <Button variant="ghost" size="sm" className="mb-4 -ml-2" asChild>
        <Link to="/results">
          <ArrowLeftIcon />
          All results
        </Link>
      </Button>

      {result.isPending && (
        <p role="status" className="text-muted-foreground text-sm">
          Loading result…
        </p>
      )}
      {result.isError && (
        <div className="grid gap-3">
          <ApiErrorAlert error={result.error} />
          {isSiftApiError(result.error) && result.error.status === 404 ? (
            <Button variant="outline" className="w-fit" asChild>
              <Link to="/results">Back to results</Link>
            </Button>
          ) : (
            <Button variant="outline" className="w-fit" onClick={() => result.refetch()}>
              Retry
            </Button>
          )}
        </div>
      )}
      {result.isSuccess && (
        <ResultDetailContent result={result.data} filter={filter} onFilterChange={setFilter} />
      )}
    </>
  )
}

interface ResultDetailContentProps {
  result: ReviewResultResponse
  filter: ResultDetailSearch
  onFilterChange: (next: ResultDetailSearch) => void
}

function ResultDetailContent({ result, filter, onFilterChange }: ResultDetailContentProps) {
  const allFindings = useMemo(() => result.findings ?? [], [result.findings])
  const files = useMemo(() => distinctFiles(allFindings), [allFindings])
  const counts = useMemo(() => countBySeverity(allFindings), [allFindings])
  const filtered = Boolean(filter.severity || filter.file)
  // the full result already carries every finding; only ask the server when a filter is active
  const findings = useReviewFindings(result.id ?? '', filter, filtered && Boolean(result.id))
  const visible = filtered ? findings.data : allFindings

  return (
    <>
      <PageHeader
        title={repositoryLabel(result.repositoryUrl)}
        description={`${result.branch ?? '—'} @ ${shortSha(result.commitSha)}`}
        actions={
          result.agentRunId ? (
            <Button variant="outline" asChild>
              <Link to="/runs/$runId" params={{ runId: result.agentRunId }}>
                <ActivityIcon />
                View run
              </Link>
            </Button>
          ) : undefined
        }
      />

      <div className="grid gap-6">
        <ResultSummary result={result} />

        <section className="grid gap-4" aria-labelledby="findings-heading">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <h2 id="findings-heading" className="text-lg font-semibold">
              Findings
              <span className="text-muted-foreground ml-2 text-sm font-normal">
                {visible ? `${visible.length} of ${allFindings.length}` : allFindings.length}
              </span>
            </h2>
            {allFindings.length > 0 && (
              <FindingFilters
                value={filter}
                files={files}
                counts={counts}
                onChange={onFilterChange}
              />
            )}
          </div>

          {allFindings.length === 0 ? (
            <EmptyState message="This review produced no findings." />
          ) : findings.isError ? (
            <div className="grid gap-3">
              <ApiErrorAlert error={findings.error} />
              <Button variant="outline" className="w-fit" onClick={() => findings.refetch()}>
                Retry
              </Button>
            </div>
          ) : !visible ? (
            <p role="status" className="text-muted-foreground text-sm">
              Loading findings…
            </p>
          ) : visible.length === 0 ? (
            <EmptyState message="No findings match these filters." />
          ) : (
            <FindingList findings={visible} />
          )}
        </section>
      </div>
    </>
  )
}

function EmptyState({ message }: { message: string }) {
  return (
    <div className="rounded-lg border border-dashed px-6 py-10 text-center">
      <p className="text-muted-foreground text-sm">{message}</p>
    </div>
  )
}
