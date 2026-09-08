import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { createTestUser, FakeAuthClient } from '@/test/fake-auth-client'
import {
  API_ORIGIN,
  pageOf,
  requireBearer,
  resultFixture,
  resultSummaryFixtures,
} from '@/test/handlers'
import { renderApp } from '@/test/render'
import { server } from '@/test/server'

const resultId = resultFixture.id as string

function renderResults(path = '/results') {
  return renderApp(path, { authClient: new FakeAuthClient(createTestUser()) })
}

/** Serves the summary fixtures and records every list request's query string. */
function serveResultList() {
  const urls: URL[] = []
  server.use(
    http.get(`${API_ORIGIN}/api/v1/results`, ({ request }) => {
      urls.push(new URL(request.url))
      return requireBearer(request) ?? HttpResponse.json(pageOf(resultSummaryFixtures))
    }),
  )
  return urls
}

function findingGroups(): HTMLElement[] {
  return screen.getAllByTestId('finding-group')
}

function severitiesOf(group: HTMLElement): (string | null)[] {
  return within(group)
    .getAllByTestId('finding')
    .map((finding) => finding.getAttribute('data-severity'))
}

describe('results list', () => {
  it('lists results with repository, branch, commit, finding count and run link', async () => {
    serveResultList()
    renderResults()

    const table = await screen.findByRole('table', { name: 'Review results' })
    const rows = within(table).getAllByTestId('result-row')
    expect(rows).toHaveLength(2)

    const first = rows[0] as HTMLElement
    expect(within(first).getByRole('link', { name: 'github.com/sift-dev/docs' })).toHaveAttribute(
      'href',
      `/results/${resultId}`,
    )
    expect(within(first).getByText('fix/typo')).toBeInTheDocument()
    expect(within(first).getByText('fffffff')).toBeInTheDocument()
    expect(within(first).getByText('3')).toBeInTheDocument()
    expect(within(first).getByRole('link', { name: '7d1e0a4c' })).toHaveAttribute(
      'href',
      `/runs/${resultFixture.agentRunId}`,
    )

    const second = rows[1] as HTMLElement
    expect(within(second).getByText('external')).toBeInTheDocument()
    expect(within(second).getByText('42')).toBeInTheDocument()
    expect(screen.getByText('Showing 1–2 of 2')).toBeInTheDocument()
  })

  it('shows an empty state when there are no results', async () => {
    renderResults()

    expect(await screen.findByText('No results yet')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('restores filters from the URL and passes them to the API', async () => {
    const urls = serveResultList()
    renderResults(`/results?agentRunId=${resultFixture.agentRunId}&commitSha=ffff`)

    await screen.findByRole('table', { name: 'Review results' })
    const last = urls.at(-1)
    expect(last?.searchParams.get('agentRunId')).toBe(resultFixture.agentRunId)
    expect(last?.searchParams.get('commitSha')).toBe('ffff')
    expect(last?.searchParams.get('page')).toBe('0')
    expect(screen.getByRole('textbox', { name: 'Commit SHA' })).toHaveValue('ffff')
    expect(screen.getByRole('button', { name: 'Remove run filter' })).toBeInTheDocument()
  })

  it('applies free-text filters on submit and clears them', async () => {
    const user = userEvent.setup()
    const urls = serveResultList()
    const { router } = renderResults()
    await screen.findByRole('table', { name: 'Review results' })

    await user.type(
      screen.getByRole('textbox', { name: 'Repository URL' }),
      'https://github.com/sift-dev/docs.git',
    )
    await user.type(screen.getByRole('textbox', { name: 'Commit SHA' }), 'ffff{Enter}')

    await waitFor(() => {
      const last = urls.at(-1)
      expect(last?.searchParams.get('repositoryUrl')).toBe('https://github.com/sift-dev/docs.git')
      expect(last?.searchParams.get('commitSha')).toBe('ffff')
    })
    expect(router.state.location.search).toMatchObject({
      repositoryUrl: 'https://github.com/sift-dev/docs.git',
      commitSha: 'ffff',
    })

    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    await waitFor(() => expect(router.state.location.search).toEqual({}))
    expect(screen.getByRole('textbox', { name: 'Commit SHA' })).toHaveValue('')
  })
})

describe('result detail', () => {
  it('renders the markdown summary, metadata and findings grouped by file', async () => {
    renderResults(`/results/${resultId}`)

    expect(
      await screen.findByRole('heading', { name: 'github.com/sift-dev/docs' }),
    ).toBeInTheDocument()
    // markdown summary is rendered as HTML, not shown raw
    expect(screen.getByRole('heading', { name: 'Overview' })).toBeInTheDocument()
    expect(screen.getByText('mostly fine').tagName).toBe('STRONG')
    expect(screen.getByRole('link', { name: 'View run' })).toHaveAttribute(
      'href',
      `/runs/${resultFixture.agentRunId}`,
    )

    const groups = findingGroups()
    expect(groups.map((group) => group.getAttribute('data-file'))).toEqual([
      'README.md',
      'src/main/kotlin/Service.kt',
    ])
    expect(severitiesOf(groups[0] as HTMLElement)).toEqual(['BLOCKER'])
    expect(severitiesOf(groups[1] as HTMLElement)).toEqual(['MAJOR', 'INFO'])

    const service = groups[1] as HTMLElement
    expect(within(service).getByText('2 findings')).toBeInTheDocument()
    expect(within(service).getByText('L42–48')).toBeInTheDocument()
    expect(within(service).getByText('L10')).toBeInTheDocument()
    expect(within(service).getByText('bug')).toBeInTheDocument()
    expect(within(service).getByText('Possible null dereference of `user`.')).toBeInTheDocument()
    // suggestion markdown
    expect(within(service).getByText('before').tagName).toBe('STRONG')
    expect(screen.getByText('3 of 3')).toBeInTheDocument()
  })

  it('filters findings by severity and file via the findings endpoint and the URL', async () => {
    const user = userEvent.setup()
    const findingUrls: URL[] = []
    server.use(
      http.get(`${API_ORIGIN}/api/v1/results/:id/findings`, ({ request }) => {
        const url = new URL(request.url)
        findingUrls.push(url)
        const severity = url.searchParams.get('severity')
        const file = url.searchParams.get('file')
        return HttpResponse.json(
          (resultFixture.findings ?? []).filter(
            (finding) =>
              (!severity || finding.severity === severity) && (!file || finding.file === file),
          ),
        )
      }),
    )
    const { router } = renderResults(`/results/${resultId}`)
    await screen.findByRole('heading', { name: 'github.com/sift-dev/docs' })
    expect(findingUrls).toHaveLength(0)

    await user.selectOptions(screen.getByRole('combobox', { name: 'Severity' }), 'INFO')
    await waitFor(() => expect(findingUrls.at(-1)?.searchParams.get('severity')).toBe('INFO'))
    await waitFor(() => {
      const groups = findingGroups()
      expect(groups).toHaveLength(1)
      expect(severitiesOf(groups[0] as HTMLElement)).toEqual(['INFO'])
    })
    expect(router.state.location.search).toMatchObject({ severity: 'INFO' })
    expect(screen.getByText('1 of 3')).toBeInTheDocument()

    await user.selectOptions(screen.getByRole('combobox', { name: 'File' }), 'README.md')
    await waitFor(() => {
      const last = findingUrls.at(-1)
      expect(last?.searchParams.get('severity')).toBe('INFO')
      expect(last?.searchParams.get('file')).toBe('README.md')
    })
    expect(await screen.findByText('No findings match these filters.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    await waitFor(() => expect(findingGroups()).toHaveLength(2))
    expect(router.state.location.search).toEqual({})
  })

  it('shows an empty state for results without findings', async () => {
    server.use(
      http.get(`${API_ORIGIN}/api/v1/results/:id`, () =>
        HttpResponse.json({ ...resultFixture, summary: '', findingCount: 0, findings: [] }),
      ),
    )
    renderResults(`/results/${resultId}`)

    expect(await screen.findByText('This review produced no findings.')).toBeInTheDocument()
    expect(screen.getByText('No summary was provided.')).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Finding filters' })).not.toBeInTheDocument()
  })

  it('shows the problem detail for an unknown result', async () => {
    renderResults('/results/00000000-0000-4000-8000-000000000000')

    expect(await screen.findByText('Result not found')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to results' })).toHaveAttribute(
      'href',
      '/results',
    )
  })
})
