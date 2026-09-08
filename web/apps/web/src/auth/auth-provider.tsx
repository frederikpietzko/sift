import { useQueryClient } from '@tanstack/react-query'
import type { User } from 'oidc-client-ts'
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'

import { setAccessTokenProvider, setUnauthorizedHandler } from '@/api/client'
import { useAuthConfig } from '@/api/hooks/auth-config'
import { AuthContext, type AuthContextValue, type AuthStatus } from '@/auth/auth-context'
import {
  CALLBACK_PATH,
  createUserManager,
  isLoginState,
  resolveAuthConfig,
  type AuthClient,
  type AuthClientFactory,
  type LoginState,
  type ResolvedAuthConfig,
} from '@/auth/oidc'

export interface AuthProviderProps {
  children: ReactNode
  /** Test seam: build the OIDC client from the resolved server config. Defaults to `UserManager`. */
  createClient?: AuthClientFactory
}

type Resolved = { config: ResolvedAuthConfig; error: null } | { config: null; error: Error }

/**
 * Bootstraps OIDC from the anonymous `/api/v1/auth/config` endpoint, keeps the current `User` in
 * React state and wires the API client: bearer tokens for every request, and a 401 → silent renew →
 * login-redirect path so data hooks never deal with authentication themselves.
 */
export function AuthProvider({ children, createClient = createUserManager }: AuthProviderProps) {
  const queryClient = useQueryClient()
  const configQuery = useAuthConfig()

  const resolved = useMemo<Resolved | null>(() => {
    if (!configQuery.isSuccess) return null
    try {
      return { config: resolveAuthConfig(configQuery.data), error: null }
    } catch (error) {
      return { config: null, error: error instanceof Error ? error : new Error(String(error)) }
    }
  }, [configQuery.isSuccess, configQuery.data])

  const client = useMemo<AuthClient | null>(
    () => (resolved?.config ? createClient(resolved.config) : null),
    [resolved, createClient],
  )

  const [user, setUser] = useState<User | null>(null)
  const [restored, setRestored] = useState(false)
  const loginInFlight = useRef(false)
  const recovery = useRef<Promise<void> | null>(null)

  // Restore the session from storage and follow the UserManager lifecycle.
  useEffect(() => {
    if (!client) return
    let cancelled = false

    void client.getUser().then((restoredUser) => {
      if (cancelled) return
      setUser(restoredUser && !restoredUser.expired ? restoredUser : null)
      setRestored(true)
    })

    const onLoaded = (loaded: User) => setUser(loaded)
    const onGone = () => {
      setUser(null)
      queryClient.clear()
    }
    client.events.addUserLoaded(onLoaded)
    client.events.addUserUnloaded(onGone)
    client.events.addAccessTokenExpired(onGone)
    client.events.addSilentRenewError(onGone)
    return () => {
      cancelled = true
      client.events.removeUserLoaded(onLoaded)
      client.events.removeUserUnloaded(onGone)
      client.events.removeAccessTokenExpired(onGone)
      client.events.removeSilentRenewError(onGone)
    }
  }, [client, queryClient])

  const getAccessToken = useCallback(async (): Promise<string | null> => {
    if (!client) return null
    const current = await client.getUser()
    if (!current) return null
    if (!current.expired) return current.access_token
    try {
      const renewed = await client.signinSilent()
      return renewed?.access_token ?? null
    } catch {
      return null
    }
  }, [client])

  const login = useCallback<AuthContextValue['login']>(
    async (options) => {
      if (!client) throw new Error('Authentication is not initialised yet')
      if (loginInFlight.current) return
      loginInFlight.current = true
      const state: LoginState = { returnTo: options?.returnTo ?? currentLocation() }
      try {
        await client.signinRedirect({ state })
      } catch (error) {
        loginInFlight.current = false
        throw error
      }
    },
    [client],
  )

  const completeLogin = useCallback<AuthContextValue['completeLogin']>(async () => {
    if (!client) throw new Error('Authentication is not initialised yet')
    const signedIn = (await client.signinCallback(window.location.href)) ?? (await client.getUser())
    if (!signedIn) throw new Error('Login did not produce a user')
    setUser(signedIn)
    loginInFlight.current = false
    return { returnTo: isLoginState(signedIn.state) ? signedIn.state.returnTo : '/' }
  }, [client])

  const logout = useCallback(async () => {
    if (!client) return
    queryClient.clear()
    try {
      await client.signoutRedirect()
    } catch {
      // provider without end-session support: at least drop the local session
      await client.removeUser()
    }
  }, [client, queryClient])

  // Wire the API client: bearer tokens, and one shared recovery path for 401s.
  useEffect(() => {
    setAccessTokenProvider(getAccessToken)
    return () => setAccessTokenProvider(() => null)
  }, [getAccessToken])

  useEffect(() => {
    if (!client) return
    setUnauthorizedHandler(() => {
      recovery.current ??= (async () => {
        try {
          const renewed = await client.signinSilent().catch(() => null)
          if (renewed) return
          await client.removeUser()
          await login()
        } finally {
          recovery.current = null
        }
      })()
    })
    return () => setUnauthorizedHandler(() => {})
  }, [client, login])

  const status: AuthStatus = configQuery.isError
    ? 'error'
    : resolved?.error
      ? 'error'
      : !client || !restored
        ? 'initializing'
        : user
          ? 'authenticated'
          : 'anonymous'

  const error = useMemo<Error | null>(() => {
    if (resolved?.error) return resolved.error
    if (!configQuery.isError) return null
    return configQuery.error instanceof Error
      ? configQuery.error
      : new Error(String(configQuery.error))
  }, [resolved, configQuery.isError, configQuery.error])

  const value = useMemo<AuthContextValue>(
    () => ({ status, user, error, login, completeLogin, logout, getAccessToken }),
    [status, user, error, login, completeLogin, logout, getAccessToken],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

/** Current SPA location; never the callback page itself so a stale state cannot loop. */
function currentLocation(): string {
  const { pathname, search, hash } = window.location
  if (pathname === CALLBACK_PATH) return '/'
  return `${pathname}${search}${hash}`
}
