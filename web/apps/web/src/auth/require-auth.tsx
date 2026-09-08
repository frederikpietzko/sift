import { useLocation } from '@tanstack/react-router'
import { useEffect, type ReactNode } from 'react'

import { useAuth } from '@/auth/auth-context'
import { describeAuthError } from '@/auth/auth-error'
import { AuthStatusScreen } from '@/auth/auth-status-screen'
import { Button } from '@/components/ui/button'

/** Renders `children` only for a signed-in user; anonymous visitors are sent to the provider. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const auth = useAuth()
  const location = useLocation()

  useEffect(() => {
    if (auth.status !== 'anonymous') return
    void auth.login({ returnTo: location.href }).catch(() => {
      // surfaced through the provider's error state on the next render
    })
  }, [auth, location.href])

  switch (auth.status) {
    case 'authenticated':
      return <>{children}</>
    case 'anonymous':
      return <AuthStatusScreen title="Redirecting to sign in…" busy />
    case 'error':
      return (
        <AuthStatusScreen
          title="Sign-in unavailable"
          description={describeAuthError(auth.error)}
          error
        >
          <Button variant="outline" onClick={() => window.location.reload()}>
            Try again
          </Button>
        </AuthStatusScreen>
      )
    default:
      return <AuthStatusScreen title="Loading…" busy />
  }
}
