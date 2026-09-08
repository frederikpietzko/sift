import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { createTestUser, FakeAuthClient, TEST_ACCESS_TOKEN } from '@/test/fake-auth-client'
import { API_ORIGIN, authConfigFixture, problemResponse } from '@/test/handlers'
import { renderApp } from '@/test/render'
import { server } from '@/test/server'

describe('authentication flow', () => {
  it('sends anonymous visitors to the provider and remembers where they wanted to go', async () => {
    const authClient = new FakeAuthClient(null)
    renderApp('/repositories?page=2', { authClient })

    await waitFor(() => expect(authClient.signinRedirect).toHaveBeenCalledTimes(1))
    expect(screen.getByRole('status')).toHaveTextContent('Redirecting to sign in')
    expect(authClient.signinRedirect).toHaveBeenCalledWith({
      state: { returnTo: '/repositories?page=2' },
    })
    expect(screen.queryByRole('navigation', { name: 'Main' })).not.toBeInTheDocument()
  })

  it('completes the login on /callback and returns to the remembered location', async () => {
    const authClient = new FakeAuthClient(null)
    authClient.callbackUser = createTestUser({ userState: { returnTo: '/repositories' } })
    const { router } = renderApp('/callback?code=abc&state=xyz', { authClient })

    await waitFor(() => expect(authClient.signinCallback).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(router.state.location.pathname).toBe('/repositories'))
    expect(await screen.findByRole('heading', { name: 'Repositories' })).toBeInTheDocument()
    expect(await screen.findByTestId('current-username')).toHaveTextContent('dev')
  })

  it('shows a retry option when the callback cannot be completed', async () => {
    const authClient = new FakeAuthClient(null)
    renderApp('/callback?code=abc&state=stale', { authClient })

    expect(await screen.findByRole('alert')).toHaveTextContent('Sign-in failed')
    expect(screen.getByText('No matching state found in storage')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(authClient.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/' } })
  })

  it('renders the shell with the username from /api/v1/me using the bearer token', async () => {
    let authorization: string | null = null
    server.use(
      http.get(`${API_ORIGIN}/api/v1/me`, ({ request }) => {
        authorization = request.headers.get('authorization')
        return HttpResponse.json({ username: 'alice', email: 'alice@example.com' })
      }),
    )
    const authClient = new FakeAuthClient(createTestUser())
    renderApp('/runs', { authClient })

    expect(await screen.findByText('alice')).toBeInTheDocument()
    expect(authorization).toBe(`Bearer ${TEST_ACCESS_TOKEN}`)
    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(nav).toHaveTextContent('Runs')
    expect(nav).toHaveTextContent('Repositories')
    expect(nav).toHaveTextContent('Results')
    expect(screen.getByRole('link', { name: /Runs/ })).toHaveAttribute('aria-current', 'page')
    expect(authClient.signinRedirect).not.toHaveBeenCalled()
  })

  it('redirects / to /runs for signed-in users', async () => {
    const { router } = renderApp('/', { authClient: new FakeAuthClient(createTestUser()) })

    await waitFor(() => expect(router.state.location.pathname).toBe('/runs'))
    expect(await screen.findByRole('heading', { name: 'Runs' })).toBeInTheDocument()
  })

  it('signs out through the user menu', async () => {
    const authClient = new FakeAuthClient(createTestUser())
    renderApp('/runs', { authClient })

    await userEvent.click(await screen.findByRole('button', { name: 'User menu' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Sign out' }))

    await waitFor(() => expect(authClient.signoutRedirect).toHaveBeenCalledTimes(1))
    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent('Redirecting to sign in'),
    )
  })

  it('on 401 tries a silent renew and falls back to a fresh login', async () => {
    server.use(
      http.get(`${API_ORIGIN}/api/v1/me`, () =>
        problemResponse(401, { title: 'Unauthorized', detail: 'Jwt expired' }),
      ),
    )
    const authClient = new FakeAuthClient(createTestUser())
    renderApp('/runs', { authClient })

    await waitFor(() => expect(authClient.signinSilent).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(authClient.signinRedirect).toHaveBeenCalledTimes(1))
    expect(authClient.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/' } })
    expect(await authClient.getUser()).toBeNull()
  })

  it('keeps the session when the silent renew after a 401 succeeds', async () => {
    let calls = 0
    server.use(
      http.get(`${API_ORIGIN}/api/v1/me`, ({ request }) => {
        calls += 1
        if (request.headers.get('authorization') !== 'Bearer renewed-token') {
          return problemResponse(401, { title: 'Unauthorized' })
        }
        return HttpResponse.json({ username: 'renewed' })
      }),
    )
    const authClient = new FakeAuthClient(createTestUser())
    const renewed = createTestUser({ access_token: 'renewed-token' })
    authClient.signinSilent.mockImplementation(async () => {
      authClient.emitLoaded(renewed)
      return renewed
    })
    const { queryClient } = renderApp('/runs', { authClient })

    await waitFor(() => expect(authClient.signinSilent).toHaveBeenCalledTimes(1))
    await queryClient.refetchQueries({ queryKey: ['me'] })
    expect(await screen.findByText('renewed')).toBeInTheDocument()
    expect(calls).toBe(2)
    expect(authClient.signinRedirect).not.toHaveBeenCalled()
  })

  it('explains when the server announces no OIDC issuer', async () => {
    server.use(
      http.get(`${API_ORIGIN}/api/v1/auth/config`, () =>
        HttpResponse.json({ ...authConfigFixture, issuerUri: null }),
      ),
    )
    renderApp('/runs')

    expect(await screen.findByRole('alert')).toHaveTextContent('Sign-in unavailable')
    expect(screen.getByText(/issuerUri missing/)).toBeInTheDocument()
  })

  it('explains when the auth config cannot be loaded', async () => {
    server.use(
      http.get(`${API_ORIGIN}/api/v1/auth/config`, () =>
        problemResponse(503, { title: 'Service Unavailable', detail: 'Warming up' }),
      ),
    )
    renderApp('/runs')

    expect(await screen.findByRole('alert')).toHaveTextContent('Sign-in unavailable')
    expect(screen.getByText(/Warming up/)).toBeInTheDocument()
  })

  it('hints at a wrong API target when the auth config endpoint answers 404', async () => {
    server.use(
      http.get(`${API_ORIGIN}/api/v1/auth/config`, () => new HttpResponse(null, { status: 404 })),
    )
    renderApp('/runs')

    expect(await screen.findByRole('alert')).toHaveTextContent('Sign-in unavailable')
    expect(screen.getByText(/not another application/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})
