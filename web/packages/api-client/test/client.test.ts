import { describe, expect, it, vi } from 'vitest'

import { createSiftClient, isSiftApiError, problemFromBody, SiftApiError } from '../src'

function jsonResponse(body: unknown, status: number, contentType = 'application/json') {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': contentType } })
}

describe('createSiftClient', () => {
  it('attaches the bearer token and returns typed data on success', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse([{ id: 'repo-1', name: 'sift', hasToken: true }], 200))
    const client = createSiftClient({
      baseUrl: 'http://sift.test',
      fetch: fetchMock,
      getAccessToken: async () => 'abc',
    })

    const { data, response } = await client.GET('/api/v1/repositories')

    expect(response.status).toBe(200)
    expect(data?.[0]?.hasToken).toBe(true)
    const request = fetchMock.mock.calls[0]?.[0] as Request
    expect(request.url).toBe('http://sift.test/api/v1/repositories')
    expect(request.headers.get('authorization')).toBe('Bearer abc')
  })

  it('sends requests anonymously when no token is available', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse({ clientId: 'sift-web', scopes: ['openid'] }, 200))
    const client = createSiftClient({
      baseUrl: 'http://sift.test',
      fetch: fetchMock,
      getAccessToken: () => null,
    })

    const { data } = await client.GET('/api/v1/auth/config')

    expect(data?.clientId).toBe('sift-web')
    expect((fetchMock.mock.calls[0]?.[0] as Request).headers.has('authorization')).toBe(false)
  })

  it('throws a SiftApiError carrying the ProblemDetail for 4xx responses', async () => {
    const problem = {
      type: 'about:blank',
      title: 'Conflict',
      status: 409,
      detail: 'Repository is referenced by 2 agent runs',
      instance: '/api/v1/repositories/repo-1',
    }
    const client = createSiftClient({
      baseUrl: 'http://sift.test',
      fetch: vi
        .fn<typeof fetch>()
        .mockResolvedValue(jsonResponse(problem, 409, 'application/problem+json')),
      getAccessToken: () => 'abc',
    })

    const failure = client.DELETE('/api/v1/repositories/{id}', {
      params: { path: { id: 'repo-1' } },
    })

    await expect(failure).rejects.toBeInstanceOf(SiftApiError)
    await failure.catch((error: unknown) => {
      if (!isSiftApiError(error)) throw error
      expect(error.status).toBe(409)
      expect(error.isConflict).toBe(true)
      expect(error.message).toBe(problem.detail)
      expect(error.problem).toEqual(problem)
    })
  })

  it('notifies onUnauthorized for 401 responses', async () => {
    const onUnauthorized = vi.fn()
    const client = createSiftClient({
      baseUrl: 'http://sift.test',
      fetch: vi
        .fn<typeof fetch>()
        .mockResolvedValue(
          jsonResponse({ title: 'Unauthorized', status: 401 }, 401, 'application/problem+json'),
        ),
      getAccessToken: () => 'expired',
      onUnauthorized,
    })

    await expect(client.GET('/api/v1/me')).rejects.toMatchObject({
      status: 401,
      isUnauthorized: true,
    })
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
    expect(onUnauthorized.mock.calls[0]?.[0]).toBeInstanceOf(SiftApiError)
  })

  it('returns the problem as `error` instead of throwing when throwOnError is false', async () => {
    const client = createSiftClient({
      baseUrl: 'http://sift.test',
      fetch: vi
        .fn<typeof fetch>()
        .mockResolvedValue(
          jsonResponse(
            { title: 'Not Found', status: 404, detail: 'no such run' },
            404,
            'application/problem+json',
          ),
        ),
      getAccessToken: () => 'abc',
      throwOnError: false,
    })

    const { data, error, response } = await client.GET('/api/v1/agents/{id}', {
      params: { path: { id: '00000000-0000-0000-0000-000000000000' } },
    })

    expect(data).toBeUndefined()
    expect(response.status).toBe(404)
    expect(error).toEqual({ title: 'Not Found', status: 404, detail: 'no such run' })
  })

  it('synthesises a ProblemDetail for non-problem error bodies', async () => {
    const client = createSiftClient({
      baseUrl: 'http://sift.test',
      fetch: vi.fn<typeof fetch>().mockResolvedValue(
        new Response('<html>Bad Gateway</html>', {
          status: 502,
          headers: { 'content-type': 'text/html' },
        }),
      ),
      getAccessToken: () => 'abc',
    })

    await expect(client.GET('/api/v1/me')).rejects.toMatchObject({
      status: 502,
      problem: { status: 502, title: 'HTTP 502', detail: '<html>Bad Gateway</html>' },
    })
  })
})

describe('problemFromBody', () => {
  it('keeps problem documents and fills in a missing status', () => {
    expect(problemFromBody(400, { title: 'Bad Request', detail: 'x' })).toEqual({
      title: 'Bad Request',
      detail: 'x',
      status: 400,
    })
  })

  it('maps arbitrary bodies to a problem with a default title', () => {
    expect(problemFromBody(404, undefined)).toEqual({
      status: 404,
      title: 'Not Found',
      detail: undefined,
    })
    expect(problemFromBody(418, ['nope'])).toEqual({
      status: 418,
      title: 'HTTP 418',
      detail: undefined,
    })
  })
})
