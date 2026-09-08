import { describe, expect, it, vi } from 'vitest'

import {
  createSiftClient,
  parseSseStream,
  SiftApiError,
  watchAgentRuns,
  type AgentRunEvent,
  type SseMessage,
} from '../src'

function streamOf(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
      controller.close()
    },
  })
}

async function collect(stream: ReadableStream<Uint8Array>, lastEventId?: string) {
  const messages: SseMessage[] = []
  for await (const message of parseSseStream(stream, lastEventId)) messages.push(message)
  return messages
}

describe('parseSseStream', () => {
  it('dispatches named events with ids and data', async () => {
    const messages = await collect(
      streamOf(['id: 1700000000000\nevent: SNAPSHOT\ndata: {"type":"SNAPSHOT"}\n\n']),
    )
    expect(messages).toEqual([
      { event: 'SNAPSHOT', data: '{"type":"SNAPSHOT"}', lastEventId: '1700000000000' },
    ])
  })

  it('joins multi-line data with newlines and strips a single leading space', async () => {
    const messages = await collect(streamOf(['data: first\ndata:second\ndata:  third\n\n']))
    expect(messages).toEqual([
      { event: 'message', data: 'first\nsecond\n third', lastEventId: undefined },
    ])
  })

  it('ignores heartbeat comment frames and empty frames', async () => {
    const messages = await collect(
      streamOf([':heartbeat\n\n', ':heartbeat\n\n', 'event: UPDATED\n\n', 'data: x\n\n']),
    )
    expect(messages).toEqual([{ event: 'message', data: 'x', lastEventId: undefined }])
  })

  it('keeps the last event id across events and honours an initial id', async () => {
    const messages = await collect(
      streamOf(['data: a\n\n', 'id: 7\ndata: b\n\n', 'data: c\n\n', 'id: 9\ndata: d\n\n']),
      '3',
    )
    expect(messages.map((m) => m.lastEventId)).toEqual(['3', '7', '7', '9'])
  })

  it('handles frames split across chunks and CRLF line endings', async () => {
    const messages = await collect(
      streamOf(['ev', 'ent: UPD', 'ATED\r', '\ndata: {"a":', '1}\r\n\r', '\ndata: tail\n\n']),
    )
    expect(messages).toEqual([
      { event: 'UPDATED', data: '{"a":1}', lastEventId: undefined },
      { event: 'message', data: 'tail', lastEventId: undefined },
    ])
  })

  it('does not dispatch a trailing frame without a terminating blank line data-less', async () => {
    const messages = await collect(streamOf(['event: SNAPSHOT\nid: 5']))
    expect(messages).toEqual([])
  })

  it('resets the event name after a dispatched event', async () => {
    const messages = await collect(streamOf(['event: SNAPSHOT\ndata: 1\n\n', 'data: 2\n\n']))
    expect(messages.map((m) => m.event)).toEqual(['SNAPSHOT', 'message'])
  })
})

describe('watchAgentRuns', () => {
  const run = { id: 'r1', phase: 'RUNNING' } as const
  const eventFrame = (id: string, type: 'SNAPSHOT' | 'UPDATED') =>
    `id: ${id}\nevent: ${type}\ndata: ${JSON.stringify({ type, run })}\n\n`

  function sseResponse(body: string, status = 200) {
    return new Response(streamOf([body]), {
      status,
      headers: { 'content-type': 'text/event-stream' },
    })
  }

  it('sends the bearer token and Last-Event-ID, then resumes from the newest id on reconnect', async () => {
    const controller = new AbortController()
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(sseResponse(':heartbeat\n\n' + eventFrame('100', 'SNAPSHOT')))
      .mockResolvedValueOnce(sseResponse(eventFrame('200', 'UPDATED')))
      .mockImplementationOnce(async () => {
        controller.abort()
        return sseResponse('')
      })
    let tokenCalls = 0
    const client = createSiftClient({
      baseUrl: 'http://sift.test',
      fetch: fetchMock,
      getAccessToken: () => `token-${++tokenCalls}`,
    })
    const events: AgentRunEvent[] = []

    await watchAgentRuns(client, {
      signal: controller.signal,
      lastEventId: '50',
      query: { mine: true },
      retryDelayMs: () => 0,
      onEvent: (event) => events.push(event),
    })

    expect(events.map((e) => e.type)).toEqual(['SNAPSHOT', 'UPDATED'])
    expect(fetchMock).toHaveBeenCalledTimes(3)
    const requests = fetchMock.mock.calls.map(([input]) => input as Request)
    expect(requests[0]?.url).toBe('http://sift.test/api/v1/agents/watch?mine=true')
    expect(requests[0]?.headers.get('accept')).toBe('text/event-stream')
    expect(requests.map((r) => r.headers.get('authorization'))).toEqual([
      'Bearer token-1',
      'Bearer token-2',
      'Bearer token-3',
    ])
    expect(requests.map((r) => r.headers.get('last-event-id'))).toEqual(['50', '100', '200'])
  })

  it('reports errors, retries retryable ones and gives up on non-retryable problems', async () => {
    const controller = new AbortController()
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ title: 'Unauthorized', status: 401 }), {
          status: 401,
          headers: { 'content-type': 'application/problem+json' },
        }),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ title: 'Bad Request', status: 400, detail: 'bad filter' }), {
          status: 400,
          headers: { 'content-type': 'application/problem+json' },
        }),
      )
    const client = createSiftClient({
      baseUrl: 'http://sift.test',
      fetch: fetchMock,
      getAccessToken: () => null,
    })
    const errors: unknown[] = []

    await expect(
      watchAgentRuns(client, {
        signal: controller.signal,
        retryDelayMs: () => 0,
        onEvent: () => {},
        onError: (error) => errors.push(error),
      }),
    ).rejects.toMatchObject({ status: 400, message: 'bad filter' })

    expect(errors).toHaveLength(2)
    expect(errors[0]).toBeInstanceOf(SiftApiError)
    expect((errors[0] as SiftApiError).status).toBe(401)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('resolves quietly when aborted while connected', async () => {
    const controller = new AbortController()
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(
      (input) =>
        new Promise((resolve, reject) => {
          const signal = (input as Request).signal
          signal.addEventListener('abort', () =>
            reject(new DOMException('The operation was aborted.', 'AbortError')),
          )
          resolve(
            new Response(
              new ReadableStream({
                start(streamController) {
                  signal.addEventListener('abort', () =>
                    streamController.error(new DOMException('aborted', 'AbortError')),
                  )
                },
              }),
              { headers: { 'content-type': 'text/event-stream' } },
            ),
          )
        }),
    )
    const client = createSiftClient({
      baseUrl: 'http://sift.test',
      fetch: fetchMock,
      getAccessToken: () => 't',
    })
    const opened = vi.fn()

    const watching = watchAgentRuns(client, {
      signal: controller.signal,
      onOpen: opened,
      onEvent: () => {},
    })
    await vi.waitFor(() => expect(opened).toHaveBeenCalled())
    controller.abort()

    await expect(watching).resolves.toBeUndefined()
  })
})
