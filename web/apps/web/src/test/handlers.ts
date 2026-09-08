import type {
  AgentRunEvent,
  AgentRunResponse,
  AuthConfigResponse,
  PageResponseAgentRunResponse,
  ProblemDetail,
  RepositoryResponse,
  ReviewFindingResponse,
  ReviewResultResponse,
  ReviewResultSummaryResponse,
  UserResponse,
} from '@sift/api-client'
import { http, HttpResponse } from 'msw'

export const API_ORIGIN = 'http://localhost:3000'

export const authConfigFixture: AuthConfigResponse = {
  clientId: 'sift-web',
  issuerUri: 'http://localhost:8180/realms/sift',
  scopes: ['openid', 'profile', 'email'],
}

export const meFixture: UserResponse = {
  id: '5c1c2b8e-0d3f-4d5a-9b8e-1f2a3b4c5d6e',
  issuer: 'http://localhost:8180/realms/sift',
  subject: 'user-1',
  username: 'dev',
  email: 'dev@sift.local',
}

export const repositoryFixtures: RepositoryResponse[] = [
  {
    id: '0f6b1c2e-1111-4a2b-9c3d-000000000001',
    name: 'sift',
    url: 'https://github.com/sift-dev/sift.git',
    hasToken: true,
    secretName: 'sift-repo-0f6b1c2e',
    createdAt: '2026-09-01T08:00:00Z',
    updatedAt: '2026-09-07T15:30:00Z',
  },
  {
    id: '0f6b1c2e-2222-4a2b-9c3d-000000000002',
    name: 'public-docs',
    url: 'https://github.com/sift-dev/docs.git',
    hasToken: false,
    secretName: null,
    createdAt: '2026-09-02T09:00:00Z',
    updatedAt: '2026-09-02T09:00:00Z',
  },
]

export const runFixtures: AgentRunResponse[] = [
  {
    id: '7d1e0a4c-aaaa-4b1e-8f00-000000000001',
    kind: 'CODE_REVIEW',
    source: 'API',
    repositoryId: '0f6b1c2e-1111-4a2b-9c3d-000000000001',
    crName: 'code-review-7d1e0a4c',
    crUid: 'k8s-uid-1',
    generation: 1,
    executionId: 'exec-1',
    phase: 'RUNNING',
    reason: 'JobStarted',
    message: 'Reviewing 12 changed files',
    spec: {
      repositoryUrl: 'https://github.com/sift-dev/sift.git',
      branch: 'feature/live-runs',
      baseBranch: 'main',
      commitSha: 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678',
      pullRequest: '42',
    },
    createdAt: '2026-09-08T09:00:00Z',
    startedAt: '2026-09-08T09:00:05Z',
    completedAt: null,
    updatedAt: '2026-09-08T09:01:00Z',
    createdBy: { id: '5c1c2b8e-0d3f-4d5a-9b8e-1f2a3b4c5d6e', username: 'dev' },
  },
  {
    id: '7d1e0a4c-bbbb-4b1e-8f00-000000000002',
    kind: 'CODE_REVIEW',
    source: 'EXTERNAL',
    repositoryId: null,
    crName: 'code-review-external',
    crUid: 'k8s-uid-2',
    generation: 3,
    executionId: 'exec-2',
    phase: 'SUCCESS',
    reason: 'Completed',
    message: null,
    spec: {
      repositoryUrl: 'https://github.com/sift-dev/docs.git',
      branch: 'fix/typo',
      baseBranch: 'main',
      commitSha: 'ffffffffffffffffffffffffffffffffffffffff',
      pullRequest: null,
    },
    createdAt: '2026-09-07T12:00:00Z',
    startedAt: '2026-09-07T12:00:03Z',
    completedAt: '2026-09-07T12:04:00Z',
    updatedAt: '2026-09-07T12:04:00Z',
    createdBy: null,
  },
]

export const findingFixtures: ReviewFindingResponse[] = [
  {
    id: 1,
    file: 'src/main/kotlin/Service.kt',
    startLine: 42,
    endLine: 48,
    severity: 'MAJOR',
    category: 'bug',
    message: 'Possible null dereference of `user`.',
    suggestion: 'Use `user?.name` or check for null **before** dereferencing.',
  },
  {
    id: 2,
    file: 'src/main/kotlin/Service.kt',
    startLine: 10,
    endLine: null,
    severity: 'INFO',
    category: 'style',
    message: 'Unused import.',
    suggestion: null,
  },
  {
    id: 3,
    file: 'README.md',
    startLine: null,
    endLine: null,
    severity: 'BLOCKER',
    category: 'security',
    message: 'Documentation contains a leaked credential.',
    suggestion: 'Rotate the credential and remove it from the file.',
  },
]

export const resultSummaryFixture: ReviewResultSummaryResponse = {
  id: '9e9e9e9e-0000-4000-8000-000000000009',
  agentRunId: '7d1e0a4c-bbbb-4b1e-8f00-000000000002',
  executionId: 'exec-2',
  repositoryUrl: 'https://github.com/sift-dev/docs.git',
  branch: 'fix/typo',
  baseBranch: 'main',
  commitSha: 'ffffffffffffffffffffffffffffffffffffffff',
  pullRequest: null,
  summary: '## Overview\n\nThe change looks **mostly fine** but has one blocker.',
  findingCount: 3,
  completedAt: '2026-09-07T12:04:00Z',
  receivedAt: '2026-09-07T12:04:02Z',
}

export const resultFixture: ReviewResultResponse = {
  ...resultSummaryFixture,
  findings: findingFixtures,
}

export const resultSummaryFixtures: ReviewResultSummaryResponse[] = [
  resultSummaryFixture,
  {
    id: '9e9e9e9e-0000-4000-8000-000000000010',
    agentRunId: null,
    executionId: 'exec-ext',
    repositoryUrl: 'https://github.com/sift-dev/sift.git',
    branch: 'feature/live-runs',
    baseBranch: 'main',
    commitSha: 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678',
    pullRequest: '42',
    summary: 'Clean.',
    findingCount: 0,
    completedAt: '2026-09-06T10:00:00Z',
    receivedAt: '2026-09-06T10:00:01Z',
  },
]

export function pageOf<T>(items: T[], page = 0, size = 20, total = items.length) {
  return { items, page, size, total }
}

export function runPage(
  items: AgentRunResponse[] = runFixtures,
  overrides: Partial<PageResponseAgentRunResponse> = {},
): PageResponseAgentRunResponse {
  return { ...pageOf(items), ...overrides }
}

/** One `text/event-stream` frame as the server writes it (`id`, `event`, `data`). */
export function sseFrame(event: AgentRunEvent, id = String(Date.now())): string {
  return `id: ${id}\nevent: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`
}

/**
 * A hand-controlled SSE connection for MSW handlers: `push()` frames to the client, `close()` ends
 * the stream (the client will reconnect). `response()` yields a fresh `Response` per connection.
 */
export function sseConnection() {
  const encoder = new TextEncoder()
  let controller: ReadableStreamDefaultController<Uint8Array> | null = null
  const opened: Request[] = []
  return {
    /** Every request that connected so far (inspect `Last-Event-ID`, query string, auth). */
    opened,
    response(request: Request) {
      opened.push(request)
      const stream = new ReadableStream<Uint8Array>({
        start(c) {
          controller = c
          c.enqueue(encoder.encode(':heartbeat\n\n'))
        },
        cancel() {
          controller = null
        },
      })
      return new HttpResponse(stream, {
        headers: { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' },
      })
    },
    push(event: AgentRunEvent, id?: string) {
      controller?.enqueue(encoder.encode(sseFrame(event, id)))
    },
    close() {
      controller?.close()
      controller = null
    },
  }
}

/** Responds like the server's `ApiExceptionHandler`: RFC 9457 problem details. */
export function problemResponse(status: number, problem: Omit<ProblemDetail, 'status'>) {
  return HttpResponse.json<ProblemDetail>(
    { type: 'about:blank', ...problem, status },
    { status, headers: { 'content-type': 'application/problem+json' } },
  )
}

/** Mirrors `SecurityConfiguration`: everything but the auth config needs a bearer token. */
export function requireBearer(request: Request): Response | null {
  const authorization = request.headers.get('authorization')
  if (authorization?.startsWith('Bearer ')) return null
  return problemResponse(401, { title: 'Unauthorized', detail: 'Full authentication is required' })
}

export const defaultHandlers = [
  http.get(`${API_ORIGIN}/api/v1/auth/config`, () => HttpResponse.json(authConfigFixture)),
  http.get(`${API_ORIGIN}/api/v1/me`, ({ request }) => {
    return requireBearer(request) ?? HttpResponse.json(meFixture)
  }),
  http.get(`${API_ORIGIN}/api/v1/repositories`, ({ request }) => {
    return requireBearer(request) ?? HttpResponse.json(repositoryFixtures)
  }),
  http.get(`${API_ORIGIN}/api/v1/repositories/:id`, ({ request, params }) => {
    const repository = repositoryFixtures.find((candidate) => candidate.id === params.id)
    if (!repository) return problemResponse(404, { title: 'Not Found' })
    return requireBearer(request) ?? HttpResponse.json(repository)
  }),
  // the watch endpoint must be matched before `/agents/:id`
  http.get(`${API_ORIGIN}/api/v1/agents/watch`, ({ request }) => {
    return requireBearer(request) ?? sseConnection().response(request)
  }),
  http.get(`${API_ORIGIN}/api/v1/agents`, ({ request }) => {
    return requireBearer(request) ?? HttpResponse.json(runPage())
  }),
  http.get(`${API_ORIGIN}/api/v1/agents/:id`, ({ request, params }) => {
    const run = runFixtures.find((candidate) => candidate.id === params.id)
    if (!run) return problemResponse(404, { title: 'Not Found', detail: 'Run not found' })
    return requireBearer(request) ?? HttpResponse.json(run)
  }),
  http.delete(`${API_ORIGIN}/api/v1/agents/:id`, ({ request, params }) => {
    const run = runFixtures.find((candidate) => candidate.id === params.id)
    if (!run) return problemResponse(404, { title: 'Not Found', detail: 'Run not found' })
    return requireBearer(request) ?? new HttpResponse(null, { status: 204 })
  }),
  http.get(`${API_ORIGIN}/api/v1/results`, ({ request }) => {
    return requireBearer(request) ?? HttpResponse.json(pageOf([]))
  }),
  http.get(`${API_ORIGIN}/api/v1/results/:id`, ({ request, params }) => {
    if (params.id !== resultFixture.id)
      return problemResponse(404, { title: 'Not Found', detail: 'Result not found' })
    return requireBearer(request) ?? HttpResponse.json(resultFixture)
  }),
  http.get(`${API_ORIGIN}/api/v1/results/:id/findings`, ({ request, params }) => {
    if (params.id !== resultFixture.id)
      return problemResponse(404, { title: 'Not Found', detail: 'Result not found' })
    const url = new URL(request.url)
    const severity = url.searchParams.get('severity')
    const file = url.searchParams.get('file')
    const findings = findingFixtures.filter(
      (finding) => (!severity || finding.severity === severity) && (!file || finding.file === file),
    )
    return requireBearer(request) ?? HttpResponse.json(findings)
  }),
]
