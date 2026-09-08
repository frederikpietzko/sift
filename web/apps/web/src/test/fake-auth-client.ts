import { User, type SigninRedirectArgs } from 'oidc-client-ts'
import { vi } from 'vitest'

import type { AuthClient, AuthClientFactory } from '@/auth'

export const TEST_ACCESS_TOKEN = 'test-access-token'

export function createTestUser(overrides: Partial<ConstructorParameters<typeof User>[0]> = {}) {
  const now = Math.floor(Date.now() / 1000)
  return new User({
    access_token: TEST_ACCESS_TOKEN,
    token_type: 'Bearer',
    expires_at: now + 3600,
    profile: {
      sub: 'user-1',
      iss: 'http://localhost:8180/realms/sift',
      aud: 'sift-web',
      exp: now + 3600,
      iat: now,
      preferred_username: 'dev',
      email: 'dev@sift.local',
    },
    ...overrides,
  })
}

type Listener<T> = (payload: T) => void

/** In-memory stand-in for `UserManager`: no network, no redirects, but the same lifecycle events. */
export class FakeAuthClient implements AuthClient {
  private user: User | null
  private readonly loaded = new Set<Listener<User>>()
  private readonly unloaded = new Set<Listener<void>>()
  private readonly expired = new Set<Listener<void>>()
  private readonly renewError = new Set<Listener<Error>>()

  readonly signinRedirect = vi.fn(async (_args?: SigninRedirectArgs) => {})
  readonly signoutRedirect = vi.fn(async () => {
    await this.removeUser()
  })
  readonly signinSilent = vi.fn(async () => null as User | null)
  /** What `signinCallback` hands back; tests set this to simulate a returning login. */
  callbackUser: User | null = null
  readonly signinCallback = vi.fn(async (_url?: string) => {
    if (!this.callbackUser) throw new Error('No matching state found in storage')
    this.user = this.callbackUser
    this.emitLoaded(this.callbackUser)
    return this.callbackUser
  })

  constructor(initialUser: User | null = null) {
    this.user = initialUser
  }

  getUser = async () => this.user

  removeUser = async () => {
    this.user = null
    this.unloaded.forEach((listener) => listener())
  }

  emitLoaded(user: User) {
    this.user = user
    this.loaded.forEach((listener) => listener(user))
  }

  emitAccessTokenExpired() {
    this.expired.forEach((listener) => listener())
  }

  readonly events: AuthClient['events'] = {
    addUserLoaded: (cb) => {
      this.loaded.add(cb)
      return () => this.loaded.delete(cb)
    },
    removeUserLoaded: (cb) => this.loaded.delete(cb),
    addUserUnloaded: (cb) => {
      this.unloaded.add(cb)
      return () => this.unloaded.delete(cb)
    },
    removeUserUnloaded: (cb) => this.unloaded.delete(cb),
    addAccessTokenExpired: (cb) => {
      this.expired.add(cb)
      return () => this.expired.delete(cb)
    },
    removeAccessTokenExpired: (cb) => this.expired.delete(cb),
    addSilentRenewError: (cb) => {
      this.renewError.add(cb)
      return () => this.renewError.delete(cb)
    },
    removeSilentRenewError: (cb) => this.renewError.delete(cb),
  }
}

export function fakeAuthClientFactory(client: FakeAuthClient): AuthClientFactory {
  return () => client
}
