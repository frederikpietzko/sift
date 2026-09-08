import { Loader2Icon, ShieldAlertIcon, ShieldCheckIcon } from 'lucide-react'
import type { ReactNode } from 'react'

interface AuthStatusScreenProps {
  title: string
  description?: string
  busy?: boolean
  error?: boolean
  children?: ReactNode
}

/** Full-page status used while the session is resolved (loading, redirecting, failure). */
export function AuthStatusScreen({
  title,
  description,
  busy,
  error,
  children,
}: AuthStatusScreenProps) {
  const Icon = error ? ShieldAlertIcon : ShieldCheckIcon
  return (
    <main className="flex min-h-full flex-col items-center justify-center gap-4 p-6 text-center">
      <Icon className={error ? 'text-destructive size-10' : 'text-primary size-10'} aria-hidden />
      <div className="flex items-center gap-2" role={error ? 'alert' : 'status'}>
        {busy && <Loader2Icon className="size-4 animate-spin" aria-hidden />}
        <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
      </div>
      {description && <p className="text-muted-foreground max-w-md text-sm">{description}</p>}
      {children}
    </main>
  )
}
