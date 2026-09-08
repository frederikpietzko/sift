import { AlertCircleIcon } from 'lucide-react'

import { describeApiError } from '@/api/errors'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'

interface ApiErrorAlertProps {
  error: unknown
  className?: string
}

/** Inline `ProblemDetail` rendering for dialogs and pages (title + detail from the server). */
export function ApiErrorAlert({ error, className }: ApiErrorAlertProps) {
  if (!error) return null
  const { title, detail } = describeApiError(error)
  return (
    <Alert variant="destructive" className={className} data-testid="api-error">
      <AlertCircleIcon />
      <AlertTitle>{title}</AlertTitle>
      <AlertDescription>{detail}</AlertDescription>
    </Alert>
  )
}
