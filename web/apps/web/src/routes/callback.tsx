import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { useEffect, useRef, useState } from 'react'

import { useAuth } from '@/auth'
import { describeAuthError } from '@/auth/auth-error'
import { AuthStatusScreen } from '@/auth/auth-status-screen'
import { Button } from '@/components/ui/button'

export const Route = createFileRoute('/callback')({
  component: CallbackPage,
})

/** OIDC redirect target: exchanges the code, then returns to where the user started. */
function CallbackPage() {
  const auth = useAuth()
  const navigate = useNavigate()
  const started = useRef(false)
  const [error, setError] = useState<Error | null>(null)

  useEffect(() => {
    if (auth.status === 'initializing' || auth.status === 'error' || started.current) return
    started.current = true
    auth
      .completeLogin()
      .then(({ returnTo }) => navigate({ to: returnTo, replace: true }))
      .catch((cause: unknown) =>
        setError(cause instanceof Error ? cause : new Error(String(cause))),
      )
  }, [auth, navigate])

  if (auth.status === 'error') {
    return (
      <AuthStatusScreen
        title="Sign-in unavailable"
        description={describeAuthError(auth.error)}
        error
      />
    )
  }
  if (error) {
    return (
      <AuthStatusScreen title="Sign-in failed" description={error.message} error>
        <Button onClick={() => void auth.login({ returnTo: '/' })}>Try again</Button>
      </AuthStatusScreen>
    )
  }
  return <AuthStatusScreen title="Completing sign-in…" busy />
}
