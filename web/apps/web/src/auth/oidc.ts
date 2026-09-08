import type { AuthConfigResponse } from '@sift/api-client'
import { UserManager, WebStorageStateStore, type UserManagerEvents } from 'oidc-client-ts'

export const CALLBACK_PATH = '/callback'

/** Where to send the user after a completed login; round-tripped through the OIDC `state`. */
export interface LoginState {
  returnTo: string
}

/**
 * The slice of `UserManager` the app relies on. Keeping it structural lets tests substitute a fake
 * without touching the network for OIDC discovery.
 */
export type AuthClient = Pick<
  UserManager,
  | 'getUser'
  | 'signinRedirect'
  | 'signinCallback'
  | 'signinSilent'
  | 'signoutRedirect'
  | 'removeUser'
> & {
  events: Pick<
    UserManagerEvents,
    | 'addUserLoaded'
    | 'removeUserLoaded'
    | 'addUserUnloaded'
    | 'removeUserUnloaded'
    | 'addAccessTokenExpired'
    | 'removeAccessTokenExpired'
    | 'addSilentRenewError'
    | 'removeSilentRenewError'
  >
}

export type AuthClientFactory = (config: ResolvedAuthConfig) => AuthClient

export interface ResolvedAuthConfig {
  issuerUri: string
  clientId: string
  scopes: string[]
}

/** Validates the anonymous `/api/v1/auth/config` payload; a missing issuer means login is impossible. */
export function resolveAuthConfig(config: AuthConfigResponse | undefined): ResolvedAuthConfig {
  if (!config?.issuerUri) {
    throw new Error('The server did not announce an OIDC issuer (issuerUri missing in auth config)')
  }
  if (!config.clientId) {
    throw new Error(
      'The server did not announce an OIDC client id (clientId missing in auth config)',
    )
  }
  return {
    issuerUri: config.issuerUri,
    clientId: config.clientId,
    scopes: config.scopes?.length ? config.scopes : ['openid', 'profile', 'email'],
  }
}

/** Authorization code + PKCE public client; tokens live in `sessionStorage` for the tab's lifetime. */
export function createUserManager(config: ResolvedAuthConfig): AuthClient {
  const origin = window.location.origin
  return new UserManager({
    authority: config.issuerUri,
    client_id: config.clientId,
    scope: config.scopes.join(' '),
    response_type: 'code',
    redirect_uri: `${origin}${CALLBACK_PATH}`,
    post_logout_redirect_uri: origin,
    automaticSilentRenew: true,
    loadUserInfo: false,
    monitorSession: false,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  })
}

export function isLoginState(state: unknown): state is LoginState {
  return (
    typeof state === 'object' &&
    state !== null &&
    typeof (state as Partial<LoginState>).returnTo === 'string'
  )
}
