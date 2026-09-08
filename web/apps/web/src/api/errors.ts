import { isSiftApiError } from '@sift/api-client'

export interface ApiErrorDescription {
  /** Short heading, e.g. the `ProblemDetail.title` ("Conflict"). */
  title: string
  /** Human-readable explanation, e.g. the `ProblemDetail.detail`. */
  detail: string
  status?: number
}

/**
 * Turns whatever a mutation/query rejected with into something we can show inline.
 * `SiftApiError` carries the server's RFC 9457 `ProblemDetail`; everything else is a generic failure.
 */
export function describeApiError(
  error: unknown,
  fallback = 'Something went wrong',
): ApiErrorDescription {
  if (isSiftApiError(error)) {
    const { title, detail } = error.problem
    return {
      title: title ?? `HTTP ${error.status}`,
      detail: detail ?? title ?? fallback,
      status: error.status,
    }
  }
  if (error instanceof Error && error.message) {
    return { title: 'Request failed', detail: error.message }
  }
  return { title: 'Request failed', detail: fallback }
}
