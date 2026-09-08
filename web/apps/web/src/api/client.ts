import { createSiftClient, type SiftApiError, type SiftClient } from '@sift/api-client'

/**
 * Token source for the API client. `AuthProvider` installs a provider backed by the
 * `oidc-client-ts` `UserManager`; until then (or without a signed-in user) requests are anonymous.
 */
export type AccessTokenProvider = () => Promise<string | null> | string | null

/**
 * Called for every `401` the server returns. `AuthProvider` uses it to renew the session or send
 * the user back to the login page, so data hooks never need their own 401 handling.
 */
export type UnauthorizedHandler = (error: SiftApiError) => void

let accessTokenProvider: AccessTokenProvider = () => null
let unauthorizedHandler: UnauthorizedHandler = () => {}

export function setAccessTokenProvider(provider: AccessTokenProvider) {
  accessTokenProvider = provider
}

export function setUnauthorizedHandler(handler: UnauthorizedHandler) {
  unauthorizedHandler = handler
}

export const siftClient: SiftClient = createSiftClient({
  getAccessToken: () => accessTokenProvider(),
  onUnauthorized: (error) => unauthorizedHandler(error),
})
