import { ChevronDownIcon } from 'lucide-react'
import type * as React from 'react'

import { cn } from '@/lib/utils'

/**
 * Styled native `<select>`: keyboard/screen-reader behaviour comes from the browser and it works
 * inside forms and tests without pointer-event polyfills. Use for short option lists.
 */
function NativeSelect({ className, children, ...props }: React.ComponentProps<'select'>) {
  return (
    <div className="relative w-full">
      <select
        data-slot="native-select"
        className={cn(
          'border-input dark:bg-input/30 dark:hover:bg-input/50 flex h-9 w-full min-w-0 appearance-none items-center rounded-md border bg-transparent py-1 pr-9 pl-3 text-sm shadow-xs transition-[color,box-shadow] outline-none disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50',
          'focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]',
          'aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive',
          className,
        )}
        {...props}
      >
        {children}
      </select>
      <ChevronDownIcon
        aria-hidden
        className="text-muted-foreground pointer-events-none absolute top-1/2 right-3 size-4 -translate-y-1/2"
      />
    </div>
  )
}

export { NativeSelect }
