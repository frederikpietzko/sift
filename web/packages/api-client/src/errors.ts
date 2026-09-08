import type { ProblemDetail } from './types'

/**
 * Error raised for non-2xx responses. Carries the RFC 9457 `ProblemDetail` body the server produced
 * (or a synthesised one when the body was not a problem document).
 */
export class SiftApiError extends Error {
  override readonly name = 'SiftApiError'
  readonly status: number
  readonly problem: ProblemDetail

  constructor(status: number, problem: ProblemDetail, options?: ErrorOptions) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${status}`, options)
    this.status = status
    this.problem = problem
  }

  get isUnauthorized(): boolean {
    return this.status === 401
  }

  get isConflict(): boolean {
    return this.status === 409
  }

  get isNotFound(): boolean {
    return this.status === 404
  }
}

export function isSiftApiError(error: unknown): error is SiftApiError {
  return error instanceof SiftApiError
}

/** Builds a `SiftApiError` from a failed response, reading the body at most once. */
export async function toSiftApiError(response: Response): Promise<SiftApiError> {
  const problem = await readProblem(response)
  return new SiftApiError(response.status, problem)
}

/** Normalises whatever the server sent into a `ProblemDetail`; never throws. */
export function problemFromBody(status: number, body: unknown): ProblemDetail {
  if (isProblemDetail(body)) {
    return { ...body, status: body.status ?? status }
  }
  return {
    status,
    title: statusTitle(status),
    detail: typeof body === 'string' && body.length > 0 ? body : undefined,
  }
}

async function readProblem(response: Response): Promise<ProblemDetail> {
  const contentType = response.headers.get('content-type') ?? ''
  let body: unknown
  try {
    if (contentType.includes('json')) {
      body = await response.json()
    } else {
      body = await response.text()
    }
  } catch {
    body = undefined
  }
  return problemFromBody(response.status, body)
}

function isProblemDetail(value: unknown): value is ProblemDetail {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const candidate = value as Record<string, unknown>
  return (
    typeof candidate.title === 'string' ||
    typeof candidate.detail === 'string' ||
    typeof candidate.status === 'number'
  )
}

function statusTitle(status: number): string {
  switch (status) {
    case 400:
      return 'Bad Request'
    case 401:
      return 'Unauthorized'
    case 403:
      return 'Forbidden'
    case 404:
      return 'Not Found'
    case 409:
      return 'Conflict'
    case 500:
      return 'Internal Server Error'
    default:
      return `HTTP ${status}`
  }
}
