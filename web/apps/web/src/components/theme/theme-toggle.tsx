import { MoonIcon, SunIcon } from 'lucide-react'

import { useTheme } from '@/components/theme/theme-context'
import { Button } from '@/components/ui/button'

export function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme()
  const next = resolvedTheme === 'dark' ? 'light' : 'dark'
  return (
    <Button
      variant="ghost"
      size="icon"
      onClick={() => setTheme(next)}
      aria-label={`Switch to ${next} theme`}
      title={`Switch to ${next} theme`}
    >
      {resolvedTheme === 'dark' ? <SunIcon aria-hidden /> : <MoonIcon aria-hidden />}
    </Button>
  )
}
