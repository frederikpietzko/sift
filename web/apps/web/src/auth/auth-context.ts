import type { User } from 'oidc-client-ts'
import { createContext, useContext } from 'react'

export type AuthStatus =
  /** Loading `/api/v1/auth/config` or restoring the session from storage. */
  | 'initializing'
  /** No (valid) session; `RequireAuth` will start a login redirect. */
  | 'anonymous'
  | 'authenticated'
  /** Login is impossible (server unreachable, no issuer configured, ...). */
  | 'error'

export interface AuthContextValue {
  status: AuthStatus
  user: User | null
  error: Error | null
  /** Starts the authorization code + PKCE redirect; defaults `returnTo` to the current URL. */
  login: (options?: { returnTo?: string }) => Promise<void>
  /** Finishes the redirect flow on `/callback` and returns where to go next. */
  completeLogin: () => Promise<{ returnTo: string }>
  logout: () => Promise<void>
  /** Current bearer token (renewed silently if expired) or `null` when signed out. */
  getAccessToken: () => Promise<string | null>
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used within <AuthProvider>')
  return value
}
