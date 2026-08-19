// Consumer-side usage of the generated client, type-checked but never run.
//
// generated.ts on its own only proves the emitted file is internally
// consistent. This file proves the API is usable the way a caller would use it:
// that a streamed member really is an AsyncIterable, that `for await` narrows
// its elements to the union, and that a mock handler can be an async generator.
//
// Anything here that stops compiling is a breaking change to the generated API.

import {
  type FeedEvent,
  type MockRequest,
  type Person,
  type StreamTransport,
  type StreamTransportRequest,
  type StreamTransportResponse,
  type Transport,
  type TransportRequest,
  type TransportResponse,
  DirectoryClient,
  FeedClient,
  FeedMock,
  NotFoundError,
  mockService,
} from './generated.js'

// --- A transport is implementable from outside ---

const transport: Transport = {
  request: async (req: TransportRequest): Promise<TransportResponse> => ({
    status: 200,
    body: {},
    headers: { 'content-type': 'application/json' },
  }),
}

const streamTransport: StreamTransport = {
  requestStream: async (req: StreamTransportRequest): Promise<StreamTransportResponse> => {
    // The framing the generator asked for is available to the transport.
    const encoding: 'ndjson' | 'binary' | undefined = req.responseStreamEncoding
    if (req.requestStreamEncoding !== undefined && req.stream !== undefined) {
      for await (const _element of req.stream) {
        // ...frame and write it
      }
    }
    return { status: 200, headers: {}, stream: (async function* () {})(), body: undefined }
  },
}

// --- Unary calls keep their shape ---

export const unary = async (): Promise<void> => {
  const directory = new DirectoryClient(transport)
  const { person, trace, code } = await directory.getPerson({ id: 'abc', verbose: true })
  const name: string = person.name
  // optional members stay optional
  const age: number | undefined = person.age
  const t: string | undefined = trace
  const c: number | undefined = code
  await directory.ping()
  void name
  void age
  void t
  void c
}

// --- A streamed response is an AsyncIterable of the union ---

export const consumeStream = async (): Promise<Person[]> => {
  const feed = new FeedClient(transport, streamTransport)
  const { events, session } = await feed.watch({ id: 'abc', since: new Date() })

  const people: Person[] = []
  for await (const event of events) {
    // the union narrows on its member key
    if ('item' in event) people.push(event.item)
    else if ('failed' in event) throw new Error(event.failed.reason)
  }
  void session
  return people
}

// --- A streamed request takes an async generator ---

export const produceStream = async (): Promise<number> => {
  const feed = new FeedClient(transport, streamTransport)

  const bytes = async function* (): AsyncGenerator<Uint8Array> {
    yield new Uint8Array([1, 2, 3])
  }
  const { stored } = await feed.upload({ id: 'abc', body: bytes() })

  // ...and both directions at once
  const outgoing = async function* (): AsyncGenerator<FeedEvent> {
    yield { completed: {} }
  }
  const echoed = await feed.echo({ incoming: outgoing() })
  for await (const _event of echoed.outgoing) {
    // typed as FeedEvent
  }
  return stored
}

// --- Declared errors are catchable as their generated classes ---

export const handleError = async (): Promise<string> => {
  const directory = new DirectoryClient(transport)
  try {
    await directory.getPerson({ id: 'missing' })
    return 'ok'
  } catch (err) {
    if (err instanceof NotFoundError) return err.payload.message
    throw err
  }
}

// --- Mocks: a streaming handler is an async generator ---

export const mock = mockService(FeedMock, {
  watch: (input) => ({
    session: 'sess-1',
    events: (async function* (): AsyncGenerator<FeedEvent> {
      yield { item: { name: input.id, kind: 'admin' } }
      yield { completed: {} }
    })(),
  }),
  upload: async (input) => {
    let stored = 0
    for await (const chunk of input.body) stored += chunk.length
    return { stored }
  },
  plain: () => ({ message: 'hi' }),
})

export const matched = (req: MockRequest): boolean => mock.match('GET', '/watch/abc') !== null
