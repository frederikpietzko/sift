import type { AgentRunResponse, CreateAgentRunRequest } from '@sift/api-client'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { createTestUser, FakeAuthClient, TEST_ACCESS_TOKEN } from '@/test/fake-auth-client'
import {
  API_ORIGIN,
  problemResponse,
  requireBearer,
  runFixtures,
  runPage,
  sseConnection,
} from '@/test/handlers'
import { renderApp } from '@/test/render'
import { server } from '@/test/server'

const runningRun = runFixtures[0] as AgentRunResponse
const externalRun = runFixtures[1] as AgentRunResponse
const runningId = runningRun.id as string
const VALID_SHA = '0123456789abcdef0123456789abcdef01234567'

function renderRuns(path = '/runs') {
  return renderApp(path, { authClient: new FakeAuthClient(createTestUser()) })
}

async function findRunsTable() {
  return screen.findByRole('table', { name: 'Runs' })
}

function rows(table: HTMLElement): HTMLElement[] {
  return within(table).getAllByTestId('run-row')
}

/** The `PhaseBadge` rendered in `container` (or the page header when omitted). */
function phaseBadge(container: ParentNode = document.body): HTMLElement | null {
  return container.querySelector<HTMLElement>('[data-slot="badge"][data-phase]')
}

/** Captures the query string of every list request so filters can be asserted. */
function captureListRequests() {
  const urls: URL[] = []
  server.use(
    http.get(`${API_ORIGIN}/api/v1/agents`, ({ request }) => {
      urls.push(new URL(request.url))
      return requireBearer(request) ?? HttpResponse.json(runPage())
    }),
  )
  return urls
}

describe('runs dashboard', () => {
  it('lists runs with phase, repository, branch, commit and creator', async () => {
    renderRuns()

    const table = await findRunsTable()
    expect(rows(table)).toHaveLength(2)

    const first = rows(table)[0] as HTMLElement
    expect(within(first).getByText('Running')).toBeInTheDocument()
    expect(within(first).getByText('sift')).toBeInTheDocument()
    expect(within(first).getByText('feature/live-runs')).toBeInTheDocument()
    expect(within(first).getByText('a1b2c3d')).toBeInTheDocument()
    expect(within(first).getByText('dev')).toBeInTheDocument()
    expect(within(first).getByRole('link', { name: 'Code review' })).toHaveAttribute(
      'href',
      `/runs/${runningId}`,
    )

    const second = rows(table)[1] as HTMLElement
    expect(within(second).getByText('Succeeded')).toBeInTheDocument()
    // no registered repository -> falls back to the URL from the spec
    expect(within(second).getByText('https://github.com/sift-dev/docs.git')).toBeInTheDocument()
    expect(within(second).getByText('external')).toBeInTheDocument()

    expect(screen.getByText('Showing 1–2 of 2')).toBeInTheDocument()
  })

  it('shows an empty state when there are no runs', async () => {
    server.use(http.get(`${API_ORIGIN}/api/v1/agents`, () => HttpResponse.json(runPage([]))))
    renderRuns()

    expect(await screen.findByText('No runs yet')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('maps filters to query parameters and the URL', async () => {
    const user = userEvent.setup()
    const urls = captureListRequests()
    const { router } = renderRuns()
    await findRunsTable()

    await user.selectOptions(screen.getByRole('combobox', { name: 'Phase' }), 'RUNNING')
    await waitFor(() => expect(router.state.location.search).toMatchObject({ phase: 'RUNNING' }))
    await user.selectOptions(screen.getByRole('combobox', { name: 'Repository' }), 'sift')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Kind' }), 'CODE_REVIEW')
    await user.click(screen.getByRole('checkbox', { name: 'Only my runs' }))

    await waitFor(() => {
      const last = urls.at(-1)
      expect(last?.searchParams.get('phase')).toBe('RUNNING')
      expect(last?.searchParams.get('repositoryId')).toBe('0f6b1c2e-1111-4a2b-9c3d-000000000001')
      expect(last?.searchParams.get('kind')).toBe('CODE_REVIEW')
      expect(last?.searchParams.get('mine')).toBe('true')
      expect(last?.searchParams.get('page')).toBe('0')
      expect(last?.searchParams.get('size')).toBe('20')
    })
    expect(router.state.location.search).toMatchObject({
      phase: 'RUNNING',
      repositoryId: '0f6b1c2e-1111-4a2b-9c3d-000000000001',
      kind: 'CODE_REVIEW',
      mine: true,
    })

    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    await waitFor(() => expect(router.state.location.search).toEqual({}))
  })

  it('restores filters from the URL and pages through results', async () => {
    const user = userEvent.setup()
    const urls: URL[] = []
    server.use(
      http.get(`${API_ORIGIN}/api/v1/agents`, ({ request }) => {
        const url = new URL(request.url)
        urls.push(url)
        const page = Number(url.searchParams.get('page') ?? 0)
        return HttpResponse.json(
          runPage(page === 0 ? [runningRun] : [externalRun], { size: 1, total: 2, page }),
        )
      }),
    )
    renderRuns('/runs?phase=SUCCESS&mine=true')

    await findRunsTable()
    expect(screen.getByRole('combobox', { name: 'Phase' })).toHaveValue('SUCCESS')
    expect(screen.getByRole('checkbox', { name: 'Only my runs' })).toBeChecked()
    expect(urls[0]?.searchParams.get('phase')).toBe('SUCCESS')
    expect(urls[0]?.searchParams.get('mine')).toBe('true')

    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Next page' }))
    await waitFor(() => expect(urls.at(-1)?.searchParams.get('page')).toBe('1'))
    expect(await screen.findByText('Page 2 of 2')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled()
    expect(within(await findRunsTable()).getByText('external')).toBeInTheDocument()
  })

  it('patches a row in place when the watch stream reports an update', async () => {
    const connection = sseConnection()
    server.use(
      http.get(`${API_ORIGIN}/api/v1/agents/watch`, ({ request }) => {
        return requireBearer(request) ?? connection.response(request)
      }),
    )
    renderRuns()
    const table = await findRunsTable()
    expect(within(rows(table)[0] as HTMLElement).getByText('Running')).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByTestId('watch-status')).toHaveAttribute('data-status', 'live'),
    )
    expect(connection.opened[0]?.headers.get('authorization')).toBe(`Bearer ${TEST_ACCESS_TOKEN}`)

    connection.push(
      {
        type: 'UPDATED',
        run: {
          ...runningRun,
          phase: 'FAILED',
          reason: 'JobFailed',
          updatedAt: '2026-09-08T09:05:00Z',
        },
      },
      '1000',
    )

    await waitFor(() =>
      expect(within(rows(table)[0] as HTMLElement).getByText('Failed')).toBeInTheDocument(),
    )
    expect(rows(table)).toHaveLength(2)
    expect(phaseBadge(rows(table)[0] as HTMLElement)).toHaveAttribute('data-phase', 'FAILED')
  })

  it('reconnects with Last-Event-ID after the stream ends', async () => {
    const connection = sseConnection()
    server.use(
      http.get(`${API_ORIGIN}/api/v1/agents/watch`, ({ request }) => {
        return requireBearer(request) ?? connection.response(request)
      }),
    )
    renderRuns()
    await findRunsTable()
    await waitFor(() => expect(connection.opened).toHaveLength(1))
    expect(connection.opened[0]?.headers.get('last-event-id')).toBeNull()

    connection.push({ type: 'UPDATED', run: runningRun }, '4242')
    await waitFor(() => expect(connection.opened).toHaveLength(1))
    connection.close()

    await waitFor(() => expect(connection.opened).toHaveLength(2), { timeout: 5_000 })
    expect(connection.opened[1]?.headers.get('last-event-id')).toBe('4242')
  })
})

describe('run detail', () => {
  it('shows spec, status and timestamps and enables cancel for active runs', async () => {
    renderRuns(`/runs/${runningId}`)

    expect(await screen.findByRole('heading', { name: 'Code review' })).toBeInTheDocument()
    expect(phaseBadge()).toHaveTextContent('Running')
    expect(screen.getByText('JobStarted')).toBeInTheDocument()
    expect(screen.getByText('Reviewing 12 changed files')).toBeInTheDocument()
    expect(screen.getByText('feature/live-runs')).toBeInTheDocument()
    expect(screen.getByText('a1b2c3d4e5f60718293a4b5c6d7e8f9012345678')).toBeInTheDocument()
    expect(screen.getByText('42')).toBeInTheDocument()
    expect(await screen.findByText('sift')).toBeInTheDocument()
    expect(
      screen.getByText('Created', { selector: 'dt' }).nextElementSibling?.querySelector('time'),
    ).toHaveAttribute('datetime', '2026-09-08T09:00:00Z')
    expect(screen.getByRole('button', { name: 'Cancel run' })).toBeEnabled()
    expect(screen.queryByRole('link', { name: 'View result' })).not.toBeInTheDocument()
  })

  it('disables cancel for terminal runs and links to the review result', async () => {
    server.use(
      http.get(`${API_ORIGIN}/api/v1/results`, ({ request }) => {
        const url = new URL(request.url)
        if (url.searchParams.get('agentRunId') !== externalRun.id)
          return HttpResponse.json(runPage([]))
        return HttpResponse.json(
          runPage([], {
            items: [
              {
                id: '9e9e9e9e-0000-4000-8000-000000000009',
                agentRunId: externalRun.id,
                findingCount: 3,
              },
            ] as never,
            total: 1,
          }),
        )
      }),
    )
    renderRuns(`/runs/${externalRun.id}`)

    expect(await screen.findByRole('heading', { name: 'Code review' })).toBeInTheDocument()
    expect(phaseBadge()).toHaveTextContent('Succeeded')
    expect(screen.getByText('external')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel run' })).toBeDisabled()
    expect(await screen.findByRole('link', { name: 'View result' })).toHaveAttribute(
      'href',
      '/results/9e9e9e9e-0000-4000-8000-000000000009',
    )
    // nothing can change any more -> no live indicator
    expect(screen.queryByTestId('watch-status')).not.toBeInTheDocument()
  })

  it('cancels a run after confirmation and reflects the new phase', async () => {
    const user = userEvent.setup()
    let cancelled = false
    server.use(
      http.post(`${API_ORIGIN}/api/v1/agents/:id/cancel`, ({ params }) => {
        cancelled = params.id === runningId
        return HttpResponse.json<AgentRunResponse>(
          { ...runningRun, phase: 'CANCELLED', reason: 'Cancelled' },
          { status: 202 },
        )
      }),
    )
    renderRuns(`/runs/${runningId}`)

    await user.click(await screen.findByRole('button', { name: 'Cancel run' }))
    const dialog = await screen.findByRole('alertdialog')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel run' }))

    await waitFor(() => expect(cancelled).toBe(true))
    await waitFor(() => expect(phaseBadge()).toHaveAttribute('data-phase', 'CANCELLED'))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Cancel run' })).toBeDisabled()
  })

  it('keeps the dialog open and shows the problem when cancelling fails', async () => {
    const user = userEvent.setup()
    server.use(
      http.post(`${API_ORIGIN}/api/v1/agents/:id/cancel`, () =>
        problemResponse(409, { title: 'Conflict', detail: 'Run already completed' }),
      ),
    )
    renderRuns(`/runs/${runningId}`)

    await user.click(await screen.findByRole('button', { name: 'Cancel run' }))
    const dialog = await screen.findByRole('alertdialog')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel run' }))

    const alert = await within(dialog).findByTestId('api-error')
    expect(alert).toHaveTextContent('Run already completed')
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
  })

  it('shows a not-found problem for unknown runs', async () => {
    renderRuns('/runs/00000000-0000-4000-8000-000000000000')

    const alert = await screen.findByTestId('api-error')
    expect(alert).toHaveTextContent('Run not found')
    expect(screen.getByRole('link', { name: 'Back to runs' })).toHaveAttribute('href', '/runs')
  })
})

describe('start review dialog', () => {
  async function openDialog(user: ReturnType<typeof userEvent.setup>) {
    await findRunsTable()
    await user.click(screen.getByRole('button', { name: 'Start review' }))
    return screen.findByRole('dialog')
  }

  it('validates the form client-side before sending anything', async () => {
    const user = userEvent.setup()
    let posted = false
    server.use(
      http.post(`${API_ORIGIN}/api/v1/agents`, () => ((posted = true), HttpResponse.json({}))),
    )
    renderRuns()
    const dialog = await openDialog(user)

    await user.type(within(dialog).getByRole('textbox', { name: 'Commit SHA' }), 'abc123')
    await user.clear(within(dialog).getByRole('textbox', { name: 'Base branch' }))
    await user.click(within(dialog).getByRole('button', { name: 'Start review' }))

    expect(await within(dialog).findByText('Choose a repository')).toBeInTheDocument()
    expect(within(dialog).getByText('Branch is required')).toBeInTheDocument()
    expect(within(dialog).getByText('Base branch is required')).toBeInTheDocument()
    expect(
      within(dialog).getByText('Commit SHA must be 40 hexadecimal characters'),
    ).toBeInTheDocument()
    expect(within(dialog).getByRole('textbox', { name: 'Commit SHA' })).toHaveAttribute(
      'aria-invalid',
      'true',
    )
    expect(posted).toBe(false)
  })

  it('creates a run and navigates to its detail page on 202', async () => {
    const user = userEvent.setup()
    let created: CreateAgentRunRequest | null = null
    const newRun: AgentRunResponse = {
      ...runningRun,
      id: '7d1e0a4c-cccc-4b1e-8f00-000000000003',
      phase: 'CREATED',
      reason: null,
      message: null,
      spec: {
        repositoryUrl: 'https://github.com/sift-dev/docs.git',
        branch: 'feature/new',
        baseBranch: 'develop',
        commitSha: VALID_SHA,
        pullRequest: null,
      },
    }
    server.use(
      http.post(`${API_ORIGIN}/api/v1/agents`, async ({ request }) => {
        created = (await request.json()) as CreateAgentRunRequest
        return HttpResponse.json(newRun, {
          status: 202,
          headers: { location: `/api/v1/agents/${newRun.id}` },
        })
      }),
      http.get(`${API_ORIGIN}/api/v1/agents/:id`, ({ params }) =>
        params.id === newRun.id
          ? HttpResponse.json(newRun)
          : problemResponse(404, { title: 'Not Found' }),
      ),
    )
    const { router } = renderRuns()
    const dialog = await openDialog(user)

    await user.selectOptions(
      within(dialog).getByRole('combobox', { name: 'Repository' }),
      'public-docs',
    )
    await user.type(within(dialog).getByRole('textbox', { name: 'Branch' }), 'feature/new')
    await user.clear(within(dialog).getByRole('textbox', { name: 'Base branch' }))
    await user.type(within(dialog).getByRole('textbox', { name: 'Base branch' }), 'develop')
    await user.type(
      within(dialog).getByRole('textbox', { name: 'Commit SHA' }),
      VALID_SHA.toUpperCase(),
    )
    await user.click(within(dialog).getByRole('button', { name: 'Start review' }))

    await waitFor(() => expect(created).not.toBeNull())
    expect(created).toEqual<CreateAgentRunRequest>({
      kind: 'CODE_REVIEW',
      repositoryId: '0f6b1c2e-2222-4a2b-9c3d-000000000002',
      branch: 'feature/new',
      baseBranch: 'develop',
      commitSha: VALID_SHA,
      pullRequest: null,
    })
    await waitFor(() => expect(router.state.location.pathname).toBe(`/runs/${newRun.id}`))
    await waitFor(() => expect(phaseBadge()).toHaveAttribute('data-phase', 'CREATED'))
    expect(screen.getByText('develop')).toBeInTheDocument()
  })

  it('shows the server problem inline when the run is rejected', async () => {
    const user = userEvent.setup()
    server.use(
      http.post(`${API_ORIGIN}/api/v1/agents`, () =>
        problemResponse(400, { title: 'Bad Request', detail: 'repository has no token' }),
      ),
    )
    renderRuns()
    const dialog = await openDialog(user)

    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Repository' }), 'sift')
    await user.type(within(dialog).getByRole('textbox', { name: 'Branch' }), 'main')
    await user.type(within(dialog).getByRole('textbox', { name: 'Commit SHA' }), VALID_SHA)
    await user.click(within(dialog).getByRole('button', { name: 'Start review' }))

    expect(await within(dialog).findByTestId('api-error')).toHaveTextContent(
      'repository has no token',
    )
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})

describe('delete run', () => {
  const deleteLabel = `Delete run Code review · ${runningRun.spec?.branch as string}`

  /** Serves the run list from a mutable array so a deletion is visible on the refetch. */
  function serveMutableList() {
    let remaining = [...runFixtures]
    const deleted: string[] = []
    server.use(
      http.get(`${API_ORIGIN}/api/v1/agents`, ({ request }) => {
        return requireBearer(request) ?? HttpResponse.json(runPage(remaining))
      }),
      http.delete(`${API_ORIGIN}/api/v1/agents/:id`, ({ params }) => {
        deleted.push(params.id as string)
        remaining = remaining.filter((run) => run.id !== params.id)
        return new HttpResponse(null, { status: 204 })
      }),
    )
    return deleted
  }

  it('requires confirmation and warns about cancellation and lost findings', async () => {
    const user = userEvent.setup()
    const deleted = serveMutableList()
    renderRuns()

    const table = await findRunsTable()
    await user.click(within(table).getByRole('button', { name: deleteLabel }))

    const dialog = await screen.findByRole('alertdialog')
    expect(within(dialog).getByText(deleteLabel.replace('Delete run ', ''))).toBeInTheDocument()
    expect(dialog).toHaveTextContent('still active and will be cancelled first')
    expect(dialog).toHaveTextContent('review result and findings are deleted permanently')
    expect(deleted).toEqual([])

    await user.click(within(dialog).getByRole('button', { name: 'Keep run' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(deleted).toEqual([])
  })

  it('deletes the run and drops the row from the list', async () => {
    const user = userEvent.setup()
    const deleted = serveMutableList()
    renderRuns()

    const table = await findRunsTable()
    expect(rows(table)).toHaveLength(2)
    await user.click(within(table).getByRole('button', { name: deleteLabel }))
    const dialog = await screen.findByRole('alertdialog')
    await user.click(within(dialog).getByRole('button', { name: 'Delete run' }))

    await waitFor(() => expect(deleted).toEqual([runningId]))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    await waitFor(() =>
      expect(screen.queryByTestId('run-row')).not.toHaveAttribute('data-run-id', runningId),
    )
    await waitFor(() => expect(rows(screen.getByRole('table', { name: 'Runs' }))).toHaveLength(1))
  })

  it('keeps the dialog open and shows the problem when deleting fails', async () => {
    const user = userEvent.setup()
    server.use(
      http.delete(`${API_ORIGIN}/api/v1/agents/:id`, () =>
        problemResponse(500, { title: 'Internal Server Error', detail: 'Cluster unreachable' }),
      ),
    )
    renderRuns()

    const table = await findRunsTable()
    await user.click(within(table).getByRole('button', { name: deleteLabel }))
    const dialog = await screen.findByRole('alertdialog')
    await user.click(within(dialog).getByRole('button', { name: 'Delete run' }))

    expect(await within(dialog).findByTestId('api-error')).toHaveTextContent('Cluster unreachable')
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
  })

  it('navigates back to the run list after deleting from the detail page', async () => {
    const user = userEvent.setup()
    const deleted = serveMutableList()
    const { router } = renderRuns(`/runs/${runningId}`)

    await user.click(await screen.findByRole('button', { name: 'Delete run' }))
    const dialog = await screen.findByRole('alertdialog')
    await user.click(within(dialog).getByRole('button', { name: 'Delete run' }))

    await waitFor(() => expect(deleted).toEqual([runningId]))
    await waitFor(() => expect(router.state.location.pathname).toBe('/runs'))
  })
})
