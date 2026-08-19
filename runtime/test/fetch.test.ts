import assert from 'node:assert/strict'
import { test } from 'node:test'
import { fetchTransport } from '../dist/fetch.js'
import { UnauthenticatedError } from '../dist/errors.js'
import type { TransportRequest } from '../dist/types.js'

type Call = { url: string; init: RequestInit }

/** A `fetch` double recording what it was called with. */
const fakeFetch = (
  respond: (call: Call) => Response,
): { fetch: typeof globalThis.fetch; calls: Call[] } => {
  const calls: Call[] = []
  return {
    calls,
    fetch: (async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const call = { url: String(input), init }
      calls.push(call)
      return respond(call)
    }) as typeof globalThis.fetch,
  }
}

const req = (over: Partial<TransportRequest> = {}): TransportRequest => ({
  operation: 'DirectoryClient.getPerson',
  method: 'GET',
  url: '/people/abc',
  ...over,
})

const json = (status: number, body: unknown, headers: Record<string, string> = {}): Response =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', ...headers },
  })

test('joins baseUrl and builds a query string, dropping undefined', async () => {
  const f = fakeFetch(() => json(200, { ok: true }))
  const t = fetchTransport({ baseUrl: '/api', fetch: f.fetch })
  await t.request(req({ query: { verbose: true, limit: 10, skip: undefined } }))
  assert.equal(f.calls[0]!.url, '/api/people/abc?verbose=true&limit=10')
})

test('a trailing slash on baseUrl does not double up', async () => {
  const f = fakeFetch(() => json(200, {}))
  await fetchTransport({ baseUrl: '/api/', fetch: f.fetch }).request(req())
  assert.equal(f.calls[0]!.url, '/api/people/abc')
})

test('lowercases response header names', async () => {
  const f = fakeFetch(() => json(409, {}, { 'X-Error-Type': 'Conflict' }))
  const res = await fetchTransport({ fetch: f.fetch }).request(req())
  assert.equal(res.headers['x-error-type'], 'Conflict')
})

test('a non-2xx status is returned, not thrown, so declared errors can dispatch', async () => {
  const f = fakeFetch(() => json(404, { message: 'nope' }))
  const res = await fetchTransport({ fetch: f.fetch }).request(req())
  assert.equal(res.status, 404)
  assert.deepEqual(res.body, { message: 'nope' })
})

test('401 throws UnauthenticatedError carrying the operation', async () => {
  const f = fakeFetch(() => json(401, {}))
  await assert.rejects(
    () => fetchTransport({ fetch: f.fetch }).request(req()),
    (err: unknown) =>
      err instanceof UnauthenticatedError && err.operation === 'DirectoryClient.getPerson',
  )
})

test('unauthenticated: false leaves the 401 as a response', async () => {
  const f = fakeFetch(() => json(401, {}))
  const res = await fetchTransport({ fetch: f.fetch, unauthenticated: false }).request(req())
  assert.equal(res.status, 401)
})

test('unauthenticated as a function throws the caller-supplied error', async () => {
  class Generated extends Error {}
  const f = fakeFetch(() => json(401, {}))
  await assert.rejects(
    () => fetchTransport({ fetch: f.fetch, unauthenticated: () => new Generated() }).request(req()),
    Generated,
  )
})

test('an empty body decodes as undefined, not a JSON error', async () => {
  // 204 must carry a null body — that is exactly the case an operation with no
  // output members produces.
  const f = fakeFetch(() => new Response(null, { status: 204 }))
  const res = await fetchTransport({ fetch: f.fetch }).request(req())
  assert.equal(res.status, 204)
  assert.equal(res.body, undefined)
})

test('a 200 with an empty body also decodes as undefined', async () => {
  const f = fakeFetch(() => new Response('', { status: 200 }))
  const res = await fetchTransport({ fetch: f.fetch }).request(req())
  assert.equal(res.body, undefined)
})

test('a non-JSON error body is surfaced as text rather than throwing', async () => {
  const f = fakeFetch(() => new Response('<html>502</html>', { status: 502 }))
  const res = await fetchTransport({ fetch: f.fetch }).request(req())
  assert.equal(res.status, 502)
  assert.equal(res.body, '<html>502</html>')
})

test('per-request headers win over the transport defaults', async () => {
  const f = fakeFetch(() => json(200, {}))
  const t = fetchTransport({ fetch: f.fetch, headers: { 'x-tenant': 'default', 'x-app': 'a' } })
  await t.request(req({ headers: { 'x-tenant': 'override' } }))
  const sent = f.calls[0]!.init.headers as Record<string, string>
  assert.equal(sent['x-tenant'], 'override')
  assert.equal(sent['x-app'], 'a')
})

test('a body is JSON-encoded and content-type set', async () => {
  const f = fakeFetch(() => json(200, {}))
  await fetchTransport({ fetch: f.fetch }).request(
    req({ method: 'POST', body: { name: 'x' } }),
  )
  const init = f.calls[0]!.init
  assert.equal(init.body, '{"name":"x"}')
  assert.equal((init.headers as Record<string, string>)['content-type'], 'application/json')
})

test('a GET sends no body and no content-type', async () => {
  const f = fakeFetch(() => json(200, {}))
  await fetchTransport({ fetch: f.fetch }).request(req())
  const init = f.calls[0]!.init
  assert.equal(init.body, undefined)
  assert.equal((init.headers as Record<string, string>)['content-type'], undefined)
})

// --- streaming ---

const ndjsonResponse = (lines: unknown[]): Response =>
  new Response(lines.map((l) => JSON.stringify(l) + '\n').join(''), {
    status: 200,
    headers: { 'content-type': 'application/x-ndjson' },
  })

test('an ndjson response is deframed into parsed elements', async () => {
  const f = fakeFetch(() => ndjsonResponse([{ item: 1 }, { completed: {} }]))
  const res = await fetchTransport({ fetch: f.fetch }).requestStream(
    { ...req({ method: 'POST' }), responseStreamEncoding: 'ndjson' },
  )
  const got: unknown[] = []
  for await (const element of res.stream!) got.push(element)
  assert.deepEqual(got, [{ item: 1 }, { completed: {} }])
})

test('a streamed response asks for the framing it expects', async () => {
  const f = fakeFetch(() => ndjsonResponse([]))
  await fetchTransport({ fetch: f.fetch }).requestStream({
    ...req({ method: 'POST' }),
    responseStreamEncoding: 'ndjson',
  })
  assert.equal((f.calls[0]!.init.headers as Record<string, string>)['accept'], 'application/x-ndjson')
})

test('a binary response is passed through as chunks', async () => {
  const f = fakeFetch(() => new Response(new Uint8Array([1, 2, 3]), { status: 200 }))
  const res = await fetchTransport({ fetch: f.fetch }).requestStream({
    ...req({ method: 'POST' }),
    responseStreamEncoding: 'binary',
  })
  const chunks: Uint8Array[] = []
  for await (const chunk of res.stream as AsyncIterable<Uint8Array>) chunks.push(chunk)
  assert.deepEqual(Array.from(chunks.flatMap((c) => Array.from(c))), [1, 2, 3])
})

test('a streamed request sends ndjson and its content-type', async () => {
  const f = fakeFetch(() => json(200, { stored: 2 }))
  const outgoing = (async function* () {
    yield { a: 1 }
    yield { a: 2 }
  })()
  await fetchTransport({ fetch: f.fetch }).requestStream({
    ...req({ method: 'POST' }),
    requestStreamEncoding: 'ndjson',
    stream: outgoing,
  })
  const init = f.calls[0]!.init
  assert.equal(
    (init.headers as Record<string, string>)['content-type'],
    'application/x-ndjson',
  )
  const sent = await new Response(init.body as BodyInit).text()
  assert.equal(sent, '{"a":1}\n{"a":2}\n')
})

test('a failure before the stream starts returns status and body, with no stream', async () => {
  const f = fakeFetch(() => json(409, { message: 'conflict' }))
  const res = await fetchTransport({ fetch: f.fetch }).requestStream({
    ...req({ method: 'POST' }),
    responseStreamEncoding: 'ndjson',
  })
  assert.equal(res.status, 409)
  assert.deepEqual(res.body, { message: 'conflict' })
  assert.equal(res.stream, undefined)
})

test('a streaming op with a unary response returns a body, not a stream', async () => {
  const f = fakeFetch(() => json(200, { stored: 3 }))
  const res = await fetchTransport({ fetch: f.fetch }).requestStream({
    ...req({ method: 'POST' }),
    requestStreamEncoding: 'binary',
    stream: (async function* () {
      yield new Uint8Array([1])
    })(),
  })
  assert.deepEqual(res.body, { stored: 3 })
  assert.equal(res.stream, undefined)
})

test('401 on a streaming request throws too', async () => {
  const f = fakeFetch(() => json(401, {}))
  await assert.rejects(
    () =>
      fetchTransport({ fetch: f.fetch }).requestStream({
        ...req({ method: 'POST' }),
        responseStreamEncoding: 'ndjson',
      }),
    UnauthenticatedError,
  )
})
