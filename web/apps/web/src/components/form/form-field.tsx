import { useId, type ReactNode } from 'react'

import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'

interface FormFieldProps {
  label: string
  description?: string
  error?: string
  optional?: boolean
  className?: string
  /** Receives the ids to wire the control up for assistive technology. */
  children: (control: {
    id: string
    'aria-describedby': string | undefined
    'aria-invalid': boolean
  }) => ReactNode
}

/** Label + control + validation message. Keeps field a11y wiring in one place. */
export function FormField({
  label,
  description,
  error,
  optional = false,
  className,
  children,
}: FormFieldProps) {
  const id = useId()
  const descriptionId = description ? `${id}-description` : undefined
  const errorId = error ? `${id}-error` : undefined
  const describedBy = [descriptionId, errorId].filter(Boolean).join(' ') || undefined

  return (
    <div className={cn('grid gap-2', className)}>
      <Label htmlFor={id}>
        {label}
        {optional && <span className="text-muted-foreground font-normal">(optional)</span>}
      </Label>
      {children({ id, 'aria-describedby': describedBy, 'aria-invalid': Boolean(error) })}
      {description && (
        <p id={descriptionId} className="text-muted-foreground text-xs">
          {description}
        </p>
      )}
      {error && (
        <p id={errorId} role="alert" className="text-destructive text-xs">
          {error}
        </p>
      )}
    </div>
  )
}
