// Proof that `@polyvariant/smithy-ts-runtime` satisfies the *generated*
// transport interfaces.
//
// The library declares its own structural copies of `Transport` /
// `StreamTransport` and imports nothing from generated code. That only works
// as long as the two stay shape-compatible — which is precisely what this file
// checks, against the real generated.ts rather than a hand-written stand-in.
//
// If the codegen changes the transport contract and the library isn't updated
// to match, this file stops compiling. That is the intended failure.

import {
  chain,
  fetchTransport,
  interceptorStack,
  tap,
  withHeaders,
} from '@polyvariant/smithy-ts-runtime'
import {
  type StreamTransport,
  type Transport,
  DirectoryClient,
  FeedClient,
  UnauthenticatedError,
} from './generated.js'

// --- The library's transport is assignable to the generated interfaces ---

const base = fetchTransport({ baseUrl: '/api' })

const transport: Transport = base
const streamTransport: StreamTransport = base

// --- ...and drives the generated clients, unary and streaming alike ---

export const unary = async (): Promise<string> => {
  const directory = new DirectoryClient(transport)
  const { person } = await directory.getPerson({ id: 'abc', verbose: true })
  return person.name
}

export const streaming = async (): Promise<number> => {
  const feed = new FeedClient(transport, streamTransport)
  const { events } = await feed.watch({ id: 'abc', since: new Date() })
  let seen = 0
  for await (const event of events) {
    if ('item' in event) seen++
  }

  const bytes = async function* (): AsyncGenerator<Uint8Array> {
    yield new Uint8Array([1, 2, 3])
  }
  await feed.upload({ id: 'abc', body: bytes() })
  return seen
}

// --- Middleware composes and still yields a generated `Transport` ---

const interceptors = interceptorStack()

export const layered: Transport = chain(
  fetchTransport({ baseUrl: '/api' }),
  interceptors.middleware,
  withHeaders(() => ({ 'x-session-id': 'abc' })),
  tap({
    onResponse: (res) => {
      void res.status
    },
    onError: (err) => {
      void err
    },
  }),
)

// A handler registered after the fact, the way a React effect would — `use`
// returns its own remover, usable directly as the effect cleanup.
export const subscribe = (onFailure: (message: string) => void): (() => void) =>
  interceptors.use({
    onError: (err) => {
      onFailure(err instanceof Error ? err.message : String(err))
    },
  })

// --- 401 can be mapped to the *generated* error class ---
//
// The library throws its own `UnauthenticatedError` by default; a project whose
// call sites check the generated class passes that class in instead.
export const withGeneratedAuthError: Transport = fetchTransport({
  baseUrl: '/api',
  unauthenticated: (operation) => new UnauthenticatedError(operation),
})
