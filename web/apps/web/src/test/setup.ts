import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, vi } from 'vitest'

import { server } from './server'

beforeAll(() => {
  // jsdom has no layout; TanStack Router's scroll restoration calls this on navigation
  vi.stubGlobal('scrollTo', vi.fn())
  // Radix Checkbox/Select observe their trigger size; jsdom does not implement ResizeObserver
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  )
  server.listen({ onUnhandledRequest: 'error' })
})
afterEach(() => {
  server.resetHandlers()
  cleanup()
})
afterAll(() => server.close())
