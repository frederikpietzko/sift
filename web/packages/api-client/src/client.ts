import createClient, { type Client, type Middleware } from 'openapi-fetch'

import { type SiftApiError, toSiftApiError } from './errors'
import type { paths } from './types'

export type SiftClient = Client<paths>

export interface SiftClientOptions {
  /**
   * Origin (and optional prefix) the API is served from, e.g. `https://sift.example.com`.
   * Defaults to the current origin (same-origin deployment behind nginx / the Vite dev proxy).
   */
  baseUrl?: string
  /** Supplies the bearer token for each request; return `null` to send the request anonymously. */
  getAccessToken: () => Promise<string | null> | string | null
  /**
   * When `true` (default) every non-2xx response is turned into a thrown `SiftApiError`, so
   * `await client.GET(...)` either yields `data` or rejects. Set to `false` to receive
   * `{ data, error, response }` and inspect the `ProblemDetail` in `error` yourself.
   */
  throwOnError?: boolean
  /** Invoked for every `401` response, e.g. to trigger a re-login. */
  onUnauthorized?: (error: SiftApiError) => void
  /** Custom `fetch`, mainly for tests and non-browser hosts. */
  fetch?: typeof globalThis.fetch
}

export function createSiftClient(options: SiftClientOptions): SiftClient {
  const client = createClient<paths>({
    baseUrl: options.baseUrl ?? defaultBaseUrl(),
    // resolve `fetch` per request so interceptors installed later (e.g. MSW in tests) apply
    fetch: options.fetch ?? ((input) => globalThis.fetch(input)),
  })
  client.use(bearerAuthMiddleware(options.getAccessToken))
  client.use(problemDetailMiddleware(options))
  return client
}

/** Adds `Authorization: Bearer <token>` when a token is available and the request has none yet. */
export function bearerAuthMiddleware(
  getAccessToken: SiftClientOptions['getAccessToken'],
): Middleware {
  return {
    async onRequest({ request }) {
      if (request.headers.has('authorization')) return request
      const token = await getAccessToken()
      if (token) request.headers.set('Authorization', `Bearer ${token}`)
      return request
    },
  }
}

/** Maps non-2xx responses to `SiftApiError` (thrown unless `throwOnError === false`). */
export function problemDetailMiddleware(
  options: Pick<SiftClientOptions, 'throwOnError' | 'onUnauthorized'>,
): Middleware {
  return {
    async onResponse({ response }) {
      if (response.ok) return response
      const error = await toSiftApiError(response.clone())
      if (error.isUnauthorized) options.onUnauthorized?.(error)
      if (options.throwOnError === false) return response
      throw error
    },
  }
}

/** Resolves `Authorization` header value for hand-rolled requests (e.g. the SSE stream). */
export async function authorizationHeader(
  getAccessToken: SiftClientOptions['getAccessToken'],
): Promise<Record<string, string>> {
  const token = await getAccessToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

function defaultBaseUrl(): string {
  if (typeof globalThis.location !== 'undefined' && globalThis.location.origin) {
    return globalThis.location.origin
  }
  return ''
}
