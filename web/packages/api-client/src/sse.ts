import type { SiftClient } from './client'
import { isSiftApiError, toSiftApiError } from './errors'
import type { AgentRunEvent, AgentWatchQuery } from './types'

/** One dispatched Server-Sent Event (WHATWG EventSource semantics). */
export interface SseMessage {
  /** Value of the `event:` field; `message` when absent, as in `EventSource`. */
  event: string
  /** Joined `data:` lines (multi-line `data` is separated by `\n`). */
  data: string
  /** Last seen `id:` field at dispatch time, if any. */
  lastEventId: string | undefined
}

/**
 * Parses a `text/event-stream` byte stream into dispatched events. Comment frames (`:heartbeat`)
 * are ignored, `data:` lines are joined with `\n`, `id:` values persist across events and an
 * event is only dispatched when it carries data (per the specification).
 */
export async function* parseSseStream(
  stream: ReadableStream<Uint8Array>,
  initialLastEventId?: string,
): AsyncGenerator<SseMessage, void, undefined> {
  const reader = stream.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let lastEventId = initialLastEventId
  let eventName = ''
  let dataLines: string[] = []

  const dispatch = (): SseMessage | undefined => {
    if (dataLines.length === 0) {
      eventName = ''
      return undefined
    }
    const message: SseMessage = {
      event: eventName || 'message',
      data: dataLines.join('\n'),
      lastEventId,
    }
    eventName = ''
    dataLines = []
    return message
  }

  const handleLine = (line: string): SseMessage | undefined => {
    if (line === '') return dispatch()
    if (line.startsWith(':')) return undefined
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    switch (field) {
      case 'event':
        eventName = value
        break
      case 'data':
        dataLines.push(value)
        break
      case 'id':
        if (!value.includes('\0')) lastEventId = value
        break
      default:
        // `retry` and unknown fields are ignored
        break
    }
    return undefined
  }

  try {
    while (true) {
      const { value, done } = await reader.read()
      buffer += done ? decoder.decode() : decoder.decode(value, { stream: true })
      let newline: number
      while ((newline = buffer.search(/\r\n|\n|\r/)) !== -1) {
        // a lone `\r` at the very end might be the first half of `\r\n`; wait for more input
        if (buffer[newline] === '\r' && newline === buffer.length - 1 && !done) break
        const line = buffer.slice(0, newline)
        buffer = buffer.slice(newline + (buffer.startsWith('\r\n', newline) ? 2 : 1))
        const message = handleLine(line)
        if (message) yield message
      }
      if (done) {
        if (buffer.length > 0) {
          const message = handleLine(buffer)
          if (message) yield message
        }
        return
      }
    }
  } finally {
    reader.releaseLock()
  }
}

export interface WatchAgentRunsOptions {
  /** Aborting the signal ends the watch (and resolves the returned promise). */
  signal: AbortSignal
  /** Server-side filters (`agentId`, `kind`, `mine`, `createdBy`). */
  query?: AgentWatchQuery
  /** Resume point sent as `Last-Event-ID` on the first connection; updated automatically afterwards. */
  lastEventId?: string
  onEvent: (event: AgentRunEvent, message: SseMessage) => void
  /** Called whenever a connection attempt was established (also on reconnects). */
  onOpen?: () => void
  /** Called for every connection error before deciding whether to reconnect. */
  onError?: (error: unknown, attempt: number) => void
  /** Return `false` to stop after `error`; defaults to retrying everything but 400/403/404. */
  shouldRetry?: (error: unknown, attempt: number) => boolean
  /** Delay before the n-th reconnect (1-based); defaults to exponential back-off capped at 30 s. */
  retryDelayMs?: (attempt: number) => number
}

const NON_RETRYABLE_STATUS = new Set([400, 403, 404])

export function defaultRetryDelayMs(attempt: number): number {
  return Math.min(30_000, 1_000 * 2 ** Math.max(0, attempt - 1))
}

export function defaultShouldRetry(error: unknown): boolean {
  return !(isSiftApiError(error) && NON_RETRYABLE_STATUS.has(error.status))
}

/**
 * Consumes `GET /api/v1/agents/watch` through the typed client so the bearer token middleware and
 * `baseUrl` apply (browsers' `EventSource` cannot send an `Authorization` header). Reconnects with
 * `Last-Event-ID` and a freshly resolved token until `signal` is aborted or `shouldRetry` declines.
 * Resolves once the watch stopped; rejects only when a non-retryable error ended it.
 */
export async function watchAgentRuns(
  client: SiftClient,
  options: WatchAgentRunsOptions,
): Promise<void> {
  const {
    signal,
    query,
    onEvent,
    onOpen,
    onError,
    shouldRetry = defaultShouldRetry,
    retryDelayMs = defaultRetryDelayMs,
  } = options
  let lastEventId = options.lastEventId
  let attempt = 0

  while (!signal.aborted) {
    try {
      const { data: stream, response } = await client.GET('/api/v1/agents/watch', {
        params: {
          query,
          header: lastEventId === undefined ? undefined : { 'Last-Event-ID': lastEventId },
        },
        headers: { Accept: 'text/event-stream' },
        parseAs: 'stream',
        signal,
      })
      // clients created with `throwOnError: false` hand the failed response back instead of throwing
      if (!response.ok) throw await toSiftApiError(response)
      if (!stream) throw new Error('Event stream response has no body')
      attempt = 0
      onOpen?.()
      for await (const message of parseSseStream(stream, lastEventId)) {
        lastEventId = message.lastEventId
        onEvent(JSON.parse(message.data) as AgentRunEvent, message)
      }
      // server closed the stream gracefully -> reconnect and resume
    } catch (error) {
      if (signal.aborted || isAbortError(error)) return
      attempt += 1
      onError?.(error, attempt)
      if (!shouldRetry(error, attempt)) throw error
    }
    if (signal.aborted) return
    await sleep(retryDelayMs(Math.max(1, attempt)), signal)
  }
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError'
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) return resolve()
    const timer = setTimeout(done, ms)
    signal.addEventListener('abort', done, { once: true })
    function done() {
      clearTimeout(timer)
      signal.removeEventListener('abort', done)
      resolve()
    }
  })
}
