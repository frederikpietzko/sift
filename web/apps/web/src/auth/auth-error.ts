import { isSiftApiError } from '@sift/api-client'

/**
 * Turns a failed auth bootstrap into an actionable message. The SPA can only sign in once
 * `GET /api/v1/auth/config` answered, so the common failures are "no server behind the API URL"
 * and "something else answers there" (e.g. a foreign process on port 8080 during local dev).
 */
export function describeAuthError(error: Error | null | undefined): string {
  if (!error) return 'Unknown error'
  if (isSiftApiError(error)) {
    if (error.isNotFound) {
      return 'The API did not provide the sign-in configuration (HTTP 404 for /api/v1/auth/config). Check that /api requests reach the Sift server and not another application on that port.'
    }
    return `The API could not provide the sign-in configuration: ${error.message}`
  }
  if (error instanceof TypeError) {
    return `The API is not reachable, so the sign-in configuration could not be loaded (${error.message}).`
  }
  return error.message
}
