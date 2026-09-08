import type {
  CreateRepositoryRequest,
  RepositoryResponse,
  UpdateRepositoryRequest,
} from '@sift/api-client'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { createTestUser, FakeAuthClient } from '@/test/fake-auth-client'
import { API_ORIGIN, problemResponse, repositoryFixtures, requireBearer } from '@/test/handlers'
import { renderApp } from '@/test/render'
import { server } from '@/test/server'

const siftRepo = repositoryFixtures[0] as RepositoryResponse
const docsRepo = repositoryFixtures[1] as RepositoryResponse

function renderRepositories() {
  return renderApp('/repositories', { authClient: new FakeAuthClient(createTestUser()) })
}

async function findTable() {
  return screen.findByRole('table', { name: 'Repositories' })
}

function rowAt(table: HTMLElement, index: number): HTMLElement {
  const row = within(table).getAllByTestId('repository-row')[index]
  if (!row) throw new Error(`No repository row at index ${index}`)
  return row
}

describe('repositories screen', () => {
  it('lists repositories with url, token badge and update time', async () => {
    renderRepositories()

    const table = await findTable()
    expect(within(table).getAllByTestId('repository-row')).toHaveLength(2)

    const first = rowAt(table, 0)
    expect(within(first).getByText('sift')).toBeInTheDocument()
    expect(within(first).getByText('https://github.com/sift-dev/sift.git')).toBeInTheDocument()
    expect(within(first).getByText('Token set')).toBeInTheDocument()
    expect(within(first).getByText(/2026/)).toHaveAttribute('datetime', '2026-09-07T15:30:00Z')

    const second = rowAt(table, 1)
    expect(within(second).getByText('public-docs')).toBeInTheDocument()
    expect(within(second).getByText('No token')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Edit public-docs' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Delete public-docs' })).toBeInTheDocument()
  })

  it('shows an empty state when nothing is registered', async () => {
    server.use(http.get(`${API_ORIGIN}/api/v1/repositories`, () => HttpResponse.json([])))
    renderRepositories()

    expect(await screen.findByText('No repositories yet')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('surfaces a failing list request inline with a retry', async () => {
    server.use(
      http.get(`${API_ORIGIN}/api/v1/repositories`, () =>
        problemResponse(500, { title: 'Internal Server Error', detail: 'database unavailable' }),
      ),
    )
    renderRepositories()

    const alert = await screen.findByTestId('api-error')
    expect(alert).toHaveTextContent('Internal Server Error')
    expect(alert).toHaveTextContent('database unavailable')
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })

  it('registers a repository and shows it in the list', async () => {
    const user = userEvent.setup()
    let created: CreateRepositoryRequest | null = null
    let repositories = [...repositoryFixtures]
    server.use(
      http.get(`${API_ORIGIN}/api/v1/repositories`, ({ request }) => {
        return requireBearer(request) ?? HttpResponse.json(repositories)
      }),
      http.post(`${API_ORIGIN}/api/v1/repositories`, async ({ request }) => {
        const unauthorized = requireBearer(request)
        if (unauthorized) return unauthorized
        created = (await request.json()) as CreateRepositoryRequest
        const repository = {
          id: '0f6b1c2e-3333-4a2b-9c3d-000000000003',
          name: created.name,
          url: created.url,
          hasToken: Boolean(created.token),
          secretName: created.token ? 'sift-repo-3333' : null,
          createdAt: '2026-09-08T10:00:00Z',
          updatedAt: '2026-09-08T10:00:00Z',
        }
        repositories = [...repositories, repository]
        return HttpResponse.json(repository, {
          status: 201,
          headers: { location: `/api/v1/repositories/${repository.id}` },
        })
      }),
    )
    renderRepositories()
    await findTable()

    await user.click(screen.getByRole('button', { name: 'Register repository' }))
    const dialog = await screen.findByRole('dialog', { name: 'Register repository' })
    await user.type(within(dialog).getByLabelText('Name'), 'new-service')
    await user.type(within(dialog).getByLabelText('Git URL'), 'https://github.com/org/new.git')
    await user.type(within(dialog).getByLabelText(/Access token/), 'ghp_secret')
    await user.click(within(dialog).getByRole('button', { name: 'Register' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(created).toEqual({
      name: 'new-service',
      url: 'https://github.com/org/new.git',
      token: 'ghp_secret',
    })
    const table = await findTable()
    await waitFor(() => expect(within(table).getAllByTestId('repository-row')).toHaveLength(3))
    expect(within(table).getByText('new-service')).toBeInTheDocument()
    expect(await screen.findByText('Repository "new-service" registered')).toBeInTheDocument()
  })

  it('validates the form before calling the server', async () => {
    const user = userEvent.setup()
    let called = false
    server.use(
      http.post(`${API_ORIGIN}/api/v1/repositories`, () => {
        called = true
        return HttpResponse.json({}, { status: 201 })
      }),
    )
    renderRepositories()
    await findTable()

    await user.click(screen.getByRole('button', { name: 'Register repository' }))
    const dialog = await screen.findByRole('dialog', { name: 'Register repository' })
    await user.type(within(dialog).getByLabelText('Git URL'), 'not a url')
    await user.click(within(dialog).getByRole('button', { name: 'Register' }))

    expect(await within(dialog).findByText('Name is required')).toBeInTheDocument()
    expect(within(dialog).getByText('Enter a valid https:// or ssh git URL')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Name')).toHaveAttribute('aria-invalid', 'true')
    expect(called).toBe(false)
  })

  it('shows the 409 problem detail inline when the name is taken', async () => {
    const user = userEvent.setup()
    server.use(
      http.post(`${API_ORIGIN}/api/v1/repositories`, () =>
        problemResponse(409, {
          title: 'Conflict',
          detail: "Repository with name 'sift' already exists",
        }),
      ),
    )
    renderRepositories()
    await findTable()

    await user.click(screen.getByRole('button', { name: 'Register repository' }))
    const dialog = await screen.findByRole('dialog', { name: 'Register repository' })
    await user.type(within(dialog).getByLabelText('Name'), 'sift')
    await user.type(within(dialog).getByLabelText('Git URL'), 'https://github.com/org/sift.git')
    await user.click(within(dialog).getByRole('button', { name: 'Register' }))

    const alert = await within(dialog).findByTestId('api-error')
    expect(alert).toHaveTextContent('Conflict')
    expect(alert).toHaveTextContent("Repository with name 'sift' already exists")
    expect(screen.getByRole('dialog', { name: 'Register repository' })).toBeInTheDocument()
  })

  it('clears the stored token from the edit dialog', async () => {
    const user = userEvent.setup()
    let updated: UpdateRepositoryRequest | null = null
    let updatedId: string | null = null
    let repositories = [...repositoryFixtures]
    server.use(
      http.get(`${API_ORIGIN}/api/v1/repositories`, () => HttpResponse.json(repositories)),
      http.put(`${API_ORIGIN}/api/v1/repositories/:id`, async ({ request, params }) => {
        const unauthorized = requireBearer(request)
        if (unauthorized) return unauthorized
        updatedId = params.id as string
        updated = (await request.json()) as UpdateRepositoryRequest
        const repository = { ...siftRepo, hasToken: false, secretName: null }
        repositories = repositories.map((r) => (r.id === repository.id ? repository : r))
        return HttpResponse.json(repository)
      }),
    )
    renderRepositories()
    await findTable()

    await user.click(screen.getByRole('button', { name: 'Edit sift' }))
    const dialog = await screen.findByRole('dialog', { name: 'Edit repository' })
    expect(within(dialog).getByLabelText('Git URL')).toHaveValue(siftRepo.url)
    expect(within(dialog).getByText('Leave empty to keep the current token.')).toBeInTheDocument()

    await user.click(within(dialog).getByRole('checkbox', { name: 'Clear the stored token' }))
    expect(within(dialog).getByLabelText(/New access token/)).toBeDisabled()
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(updatedId).toBe(siftRepo.id)
    expect(updated).toEqual({ url: null, token: null, clearToken: true })
    const table = await findTable()
    await waitFor(() => expect(within(rowAt(table, 0)).getByText('No token')).toBeInTheDocument())
  })

  it('rotates the token and url without offering "clear" for token-less repositories', async () => {
    const user = userEvent.setup()
    let updated: UpdateRepositoryRequest | null = null
    server.use(
      http.put(`${API_ORIGIN}/api/v1/repositories/:id`, async ({ request }) => {
        updated = (await request.json()) as UpdateRepositoryRequest
        return HttpResponse.json({ ...docsRepo, url: updated.url, hasToken: true })
      }),
    )
    renderRepositories()
    await findTable()

    await user.click(screen.getByRole('button', { name: 'Edit public-docs' }))
    const dialog = await screen.findByRole('dialog', { name: 'Edit repository' })
    expect(within(dialog).queryByRole('checkbox')).not.toBeInTheDocument()

    await user.clear(within(dialog).getByLabelText('Git URL'))
    await user.type(within(dialog).getByLabelText('Git URL'), 'git@github.com:sift-dev/docs.git')
    await user.type(within(dialog).getByLabelText(/New access token/), 'ghp_rotated')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(updated).toEqual({
      url: 'git@github.com:sift-dev/docs.git',
      token: 'ghp_rotated',
      clearToken: false,
    })
  })

  it('deletes a repository after confirmation', async () => {
    const user = userEvent.setup()
    let deletedId: string | null = null
    let repositories = [...repositoryFixtures]
    server.use(
      http.get(`${API_ORIGIN}/api/v1/repositories`, () => HttpResponse.json(repositories)),
      http.delete(`${API_ORIGIN}/api/v1/repositories/:id`, ({ request, params }) => {
        const unauthorized = requireBearer(request)
        if (unauthorized) return unauthorized
        deletedId = params.id as string
        repositories = repositories.filter((r) => r.id !== deletedId)
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderRepositories()
    await findTable()

    await user.click(screen.getByRole('button', { name: 'Delete public-docs' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Delete repository?' })
    expect(dialog).toHaveTextContent('public-docs')
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(deletedId).toBe(docsRepo.id)
    const table = await findTable()
    await waitFor(() => expect(within(table).getAllByTestId('repository-row')).toHaveLength(1))
    expect(within(table).queryByText('public-docs')).not.toBeInTheDocument()
  })

  it('keeps the delete dialog open and shows the 409 conflict when runs are still active', async () => {
    const user = userEvent.setup()
    server.use(
      http.delete(`${API_ORIGIN}/api/v1/repositories/:id`, () =>
        problemResponse(409, {
          title: 'Conflict',
          detail: 'Repository has 2 active agent runs',
        }),
      ),
    )
    renderRepositories()
    await findTable()

    await user.click(screen.getByRole('button', { name: 'Delete sift' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Delete repository?' })
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }))

    const alert = await within(dialog).findByTestId('api-error')
    expect(alert).toHaveTextContent('Conflict')
    expect(alert).toHaveTextContent('Repository has 2 active agent runs')
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(within(await findTable()).getAllByTestId('repository-row')).toHaveLength(2)
  })
})
