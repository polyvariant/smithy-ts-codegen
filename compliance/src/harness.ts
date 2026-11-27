// Runs one smithy HTTP protocol compliance case against a generated client.
//
// The client is driven through the real `fetchTransport` from the runtime
// library, with a `fetch` double underneath — so a case checks the whole client
// path a user gets, including the runtime's URL and body encoding, not just the
// structured request the generated code hands its transport.
//
//   request cases   call the operation with the case's `params` and compare the
//                   request that reached `fetch` with the expected method, URI,
//                   query, headers and body.
//   response cases  answer with the case's status, headers and body, and compare
//                   what the client returned (or the declared error it threw)
//                   with `params`.
//
// Cases come from compliance/generated/<suite>/cases.json, written by
// `sbt complianceCodegen`; their `params` are already in the shape the TS API
// uses (see ComplianceCodegen.toTsValue), except that timestamps are
// `{"$date": <epoch seconds>}`, which JSON can't express natively.

import { fetchTransport } from '@polyvariant/smithy-ts-runtime'
import { isDeepStrictEqual } from 'node:util'

/** The fields of smithy's `HttpRequestTestCase` / `HttpResponseTestCase` used here. */
export interface HttpTestCase {
  id: string
  method?: string
  uri?: string
  host?: string
  queryParams?: string[]
  forbidQueryParams?: string[]
  requireQueryParams?: string[]
  headers?: Record<string, string>
  forbidHeaders?: string[]
  requireHeaders?: string[]
  body?: string
  bodyMediaType?: string
  code?: number
  params?: unknown
}

/** How one side of an operation streams: the member that holds the stream, and its framing. */
export interface StreamInfo {
  member: string
  encoding: 'ndjson' | 'sse' | 'binary'
  terminator?: string
}

/** One event of an `@eventStreamTests` case. `params` is absent for an unmodeled event (the
 * terminator, or bytes a reader must skip); `bytes` is base64 of the exact framed event. */
export interface StreamEvent {
  type: 'request' | 'response'
  params?: unknown
  body?: string
  bytes?: string
}

/** The fields of smithy's `EventStreamTestCase` used here, with `expectation` flattened. */
export interface EventStreamCase {
  id: string
  initialRequestParams?: unknown
  initialRequest?: { method?: string; uri?: string; queryParams?: string[]; headers?: Record<string, string>; body?: string }
  initialResponseParams?: unknown
  initialResponse?: { code?: number; headers?: Record<string, string>; body?: string }
  events?: StreamEvent[]
  expectation: 'success' | 'failure'
}

interface CaseBase {
  id: string
  service: string
  operation: string
  /** The error shape a response case decodes to, or `null` for the operation's output. */
  error: string | null
  /** What the operation streams each way; absent when it streams nothing. */
  streams?: { input: StreamInfo | null; output: StreamInfo | null } | null
}

export type ComplianceCase =
  | (CaseBase & { kind: 'request' | 'response'; case: HttpTestCase })
  | (CaseBase & { kind: 'events'; case: EventStreamCase })

/** A generated.ts module, looked up by export name. */
export type GeneratedModule = Record<string, unknown>

type Client = Record<string, unknown>
type ClientClass = new (transport: unknown, streamTransport: unknown) => Client

const lowerFirst = (s: string): string => s.charAt(0).toLowerCase() + s.slice(1)

export const reviveDates = (v: unknown): unknown => {
  if (Array.isArray(v)) return v.map(reviveDates)
  if (v === null || typeof v !== 'object') return v
  if ('$date' in v && typeof v.$date === 'number') return new Date(v.$date * 1000)
  return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, reviveDates(x)]))
}

/** As a JSON value: drops `undefined` members, `Date` becomes its ISO string. */
const asJson = (v: unknown): unknown =>
  v === undefined
    ? undefined
    : JSON.parse(JSON.stringify(v, (_k, x: unknown) => (typeof x === 'bigint' ? Number(x) : x)))

const isJsonCase = (c: HttpTestCase): boolean =>
  (c.bodyMediaType ?? '').includes('json') ||
  Object.entries(c.headers ?? {}).some(
    ([k, v]) => k.toLowerCase() === 'content-type' && v.includes('json'),
  )

export const describe = (e: unknown): string =>
  (e instanceof Error ? `${e.constructor.name}: ${e.message}` : String(e)).replace(/\s+/g, ' ').slice(0, 300)

const nullBodyStatus = (status: number): boolean => [204, 205, 304].includes(status)

const parseJson = (text: string): { ok: true; value: unknown } | { ok: false } => {
  try {
    return { ok: true, value: JSON.parse(text) }
  } catch {
    return { ok: false }
  }
}

/** Whether `part` is already percent-encoded. Some cases (alloy's) spell an expected
 * query parameter decoded — `query=the query` — which smithy4s compares decoded. */
const isEncoded = (part: string): boolean => /^[A-Za-z0-9\-._~%=!$&'()*+,;:@/?]*$/.test(part)

const decodeQueryPart = (part: string): string => {
  try {
    return decodeURIComponent(part)
  } catch {
    return part
  }
}

/** The operation `tc` exercises, on a client over `transport`: a function from its input to
 * what the client method resolves to — or why there is none. */
export const operationOf = (
  gen: GeneratedModule,
  tc: ComplianceCase,
  transport: unknown,
): ((input: unknown) => Promise<unknown>) | string => {
  const Client = gen[`${tc.service}Client`] as ClientClass | undefined
  if (Client === undefined) return `no ${tc.service}Client export`
  const client = new Client(transport, transport)
  const method = client[lowerFirst(tc.operation)]
  if (typeof method !== 'function') return `no ${lowerFirst(tc.operation)} method`
  // Operations without input members take only `opts`.
  return (input) => (method.length >= 2 ? method.call(client, input) : method.call(client))
}

/** Runs `tc` against the generated module; returns what didn't match (empty means it passed). */
export const runCase = async (gen: GeneratedModule, tc: ComplianceCase): Promise<string[]> => {
  if (tc.kind === 'events') return runEventCase(gen, tc)
  const c = tc.case

  let sent: Sent | undefined
  const reply = (): Response =>
    tc.kind === 'response'
      ? new Response(nullBodyStatus(c.code ?? 200) || (!c.body && tc.streams?.output == null) ? null : (c.body ?? ''), {
          status: c.code ?? 200,
          headers: c.headers ?? {},
        })
      : new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } })
  const transport = fetchTransport({
    baseUrl: c.host === undefined ? '' : `https://${c.host}`,
    unauthenticated: false,
    fetch: async (url, init) => {
      sent = { url: String(url), init: init ?? {}, body: await readBody(init?.body) }
      return reply()
    },
  })
  const call = operationOf(gen, tc, transport)
  if (typeof call === 'string') return [call]

  if (tc.kind === 'request') {
    try {
      await call(withByteStream(reviveDates(c.params ?? {}), tc.streams?.input))
    } catch (e) {
      // The response is a stub; only a throw before anything was sent matters.
      if (sent === undefined) return [`threw before sending: ${describe(e)}`]
    }
    if (sent === undefined) return ['no request was sent']
    return checkRequest(c, sent)
  }
  return checkResponse(gen, tc, async () => readByteStream(await call({}), tc.streams?.output))
}

/** What reached `fetch`. The body is read here, as it may be a stream. */
interface Sent {
  url: string
  init: RequestInit
  body: string | undefined
}

const readBody = async (body: RequestInit['body']): Promise<string | undefined> =>
  body == null ? undefined : typeof body === 'string' ? body : await new Response(body).text()

const checkRequest = (c: HttpTestCase, sent: Sent): string[] => {
  const problems: string[] = []
  const [rawPath = '', rawQuery] = sent.url.replace(/^https?:\/\/[^/]+/, '').split('?', 2) as [
    string,
    string | undefined,
  ]
  const queryParts = rawQuery === undefined || rawQuery === '' ? [] : rawQuery.split('&')
  const queryKeys = new Set(queryParts.map((p) => decodeURIComponent(p.split('=')[0] ?? '')))
  const headers = new Headers(sent.init.headers)

  const method = sent.init.method ?? 'GET'
  if (c.method !== undefined && method !== c.method) problems.push(`method ${method}, expected ${c.method}`)
  if (c.uri !== undefined && rawPath !== c.uri) problems.push(`path ${rawPath}, expected ${c.uri}`)
  const decodedParts = queryParts.map(decodeQueryPart)
  const missing = (c.queryParams ?? []).filter((q) =>
    isEncoded(q) ? !queryParts.includes(q) : !decodedParts.includes(q),
  )
  if (missing.length > 0) problems.push(`query lacks ${missing.join(' & ')} (sent ${rawQuery ?? 'none'})`)
  for (const k of c.forbidQueryParams ?? []) if (queryKeys.has(k)) problems.push(`forbidden query param ${k}`)
  for (const k of c.requireQueryParams ?? []) if (!queryKeys.has(k)) problems.push(`missing query param ${k}`)
  for (const [k, v] of Object.entries(c.headers ?? {})) {
    if (headers.get(k) !== v) problems.push(`header ${k}: ${headers.get(k)}, expected ${v}`)
  }
  for (const k of c.forbidHeaders ?? []) if (headers.has(k)) problems.push(`forbidden header ${k}`)
  for (const k of c.requireHeaders ?? []) {
    // fetch sets Content-Length itself; it never appears in the init it was given.
    if (k.toLowerCase() !== 'content-length' && !headers.has(k)) problems.push(`missing header ${k}`)
  }
  if (c.body !== undefined) {
    const actual = sent.body ?? ''
    if (c.body.trim() === '') {
      if (actual !== '') problems.push(`body ${actual}, expected none`)
    } else if (isJsonCase(c) || (parseJson(c.body).ok && parseJson(actual).ok)) {
      // As JSON whenever it is JSON, even when the case doesn't say so: key order and
      // whitespace are not part of the encoding.
      const parsed = parseJson(actual)
      const expected: unknown = JSON.parse(c.body)
      if (!parsed.ok || !isDeepStrictEqual(parsed.value, expected)) {
        problems.push(`body ${actual || '(none)'}, expected ${JSON.stringify(expected)}`)
      }
    } else if (actual !== c.body) {
      problems.push(`body ${JSON.stringify(actual)}, expected ${JSON.stringify(c.body)}`)
    }
  }
  return problems
}

/** Calls the operation and compares what it returned, or the declared error it threw, with
 * the case's `params`. */
export const checkResponse = async (
  gen: GeneratedModule,
  tc: CaseBase & { kind: 'request' | 'response'; case: HttpTestCase },
  call: () => Promise<unknown>,
): Promise<string[]> => {
  const expected = asJson(reviveDates(tc.case.params ?? {}))
  let out: unknown
  try {
    out = await call()
  } catch (e) {
    if (tc.error === null) return [`threw ${describe(e)}`]
    const ErrorClass = gen[`${tc.error}Error`] as (abstract new (...args: never[]) => unknown) | undefined
    if (ErrorClass === undefined) return [`no ${tc.error}Error export`]
    if (!(e instanceof ErrorClass)) return [`threw ${describe(e)}, expected ${tc.error}Error`]
    const payload = asJson((e as { payload?: unknown }).payload)
    return isDeepStrictEqual(payload, expected)
      ? []
      : [`error payload ${JSON.stringify(payload)}, expected ${JSON.stringify(expected)}`]
  }
  if (tc.error !== null) return [`returned ${JSON.stringify(asJson(out))}, expected ${tc.error}Error to be thrown`]
  const actual = asJson(out) ?? {}
  return isDeepStrictEqual(actual, expected)
    ? []
    : [`output ${JSON.stringify(actual)}, expected ${JSON.stringify(expected)}`]
}

// --- Streamed blobs ---
//
// A case writes a streamed blob as its text; the client takes and returns it
// as a stream of bytes.

const encoder = new TextEncoder()

const once = async function* <A>(values: readonly A[]): AsyncGenerator<A> {
  for (const v of values) yield v
}

/** `params` with a streamed blob's text turned into the byte stream the client takes. */
const withByteStream = (params: unknown, stream: StreamInfo | null | undefined): unknown => {
  if (stream?.encoding !== 'binary' || params === null || typeof params !== 'object') return params
  const text = (params as Record<string, unknown>)[stream.member]
  return { ...params, [stream.member]: once(typeof text === 'string' ? [encoder.encode(text)] : []) }
}

/** `out` with a streamed blob read back into its text. */
const readByteStream = async (out: unknown, stream: StreamInfo | null | undefined): Promise<unknown> => {
  if (stream?.encoding !== 'binary' || out === null || typeof out !== 'object') return out
  const chunks = (out as Record<string, unknown>)[stream.member] as AsyncIterable<Uint8Array> | undefined
  if (chunks === undefined) return out
  const parts: Uint8Array[] = []
  for await (const chunk of chunks) parts.push(chunk)
  return { ...out, [stream.member]: Buffer.concat(parts).toString('utf8') }
}

// --- @eventStreamTests ---

/** The bytes an event stands for: its `bytes`, or its `body` framed as the protocol writes it. */
const eventBytes = (e: StreamEvent, stream: StreamInfo): Uint8Array =>
  e.bytes !== undefined ? Buffer.from(e.bytes, 'base64') : encoder.encode(frame(e.body ?? '', stream))

const frame = (data: string, stream: StreamInfo): string =>
  stream.encoding === 'sse'
    ? data.split('\n').map((line) => `data: ${line}\n`).join('') + '\n'
    : `${data}\n`

const show = (s: string): string => JSON.stringify(s)

/** The `data` of each event in a written body — strictly: whatever a reader would forgive (a blank
 * line, `\r\n`, a comment) is a problem here, as the protocol writes none of it. */
const splitFrames = (body: string, stream: StreamInfo): { frames: string[] } | { problem: string } => {
  if (body === '') return { frames: [] }
  const terminator = stream.encoding === 'sse' ? '\n\n' : '\n'
  if (!body.endsWith(terminator)) return { problem: `the body doesn't end in ${show(terminator)}: ${show(body)}` }
  const pieces = body.slice(0, -terminator.length).split(terminator)
  if (stream.encoding === 'ndjson') {
    const bad = pieces.find((line) => line === '' || line.includes('\r'))
    return bad === undefined ? { frames: pieces } : { problem: `unexpected line ${show(bad)}` }
  }
  const frames: string[] = []
  for (const event of pieces) {
    const lines = event.split('\n')
    const bad = lines.find((line) => !line.startsWith('data: '))
    if (bad !== undefined) return { problem: `unexpected line in an event: ${show(bad)}` }
    frames.push(lines.map((line) => line.slice('data: '.length)).join('\n'))
  }
  return { frames }
}

/** The written `body`, split into events, against `events`: a modeled event's data compared as
 * JSON, an unmodeled one's (the terminator) as text. */
const checkEvents = (what: string, body: string, events: StreamEvent[], stream: StreamInfo): string[] => {
  const split = splitFrames(body, stream)
  if ('problem' in split) return [`${what}: ${split.problem}`]
  if (split.frames.length !== events.length) {
    return [`${what}: wrote ${split.frames.length} events, expected ${events.length}: ${show(body)}`]
  }
  return split.frames.flatMap((actual, i) => {
    const e = events[i]
    const expected = e?.body ?? ''
    if (e?.params === undefined) return actual === expected ? [] : [`${what}: event ${show(actual)}, expected ${show(expected)}`]
    const parsed = parseJson(actual)
    return parsed.ok && isDeepStrictEqual(parsed.value, JSON.parse(expected))
      ? []
      : [`${what}: event ${actual}, expected ${expected}`]
  })
}

/**
 * Runs an `@eventStreamTests` case as the client: calls the operation with
 * `initialRequestParams` and the `request` events' values as its stream,
 * answers with `initialResponse` and the `response` events as its body, then
 * checks what was sent against `initialRequest` and the `request` events, and
 * what came back against `initialResponseParams` and the `response` events'
 * values. A case expected to fail must throw, from the call or from reading
 * the stream it returned.
 */
const runEventCase = async (
  gen: GeneratedModule,
  tc: CaseBase & { kind: 'events'; case: EventStreamCase },
): Promise<string[]> => {
  const c = tc.case
  const Client = gen[`${tc.service}Client`] as ClientClass | undefined
  if (Client === undefined) return [`no ${tc.service}Client export`]
  const events = c.events ?? []
  const requestEvents = events.filter((e) => e.type === 'request')
  const responseEvents = events.filter((e) => e.type === 'response')
  const streamIn = tc.streams?.input ?? null
  const streamOut = tc.streams?.output ?? null

  let sent: Sent | undefined
  const transport = fetchTransport({
    unauthenticated: false,
    fetch: async (url, init) => {
      sent = { url: String(url), init: init ?? {}, body: await readBody(init?.body) }
      const status = c.initialResponse?.code ?? 200
      const body =
        streamOut === null
          ? (c.initialResponse?.body ?? '')
          : Buffer.concat(responseEvents.map((e) => eventBytes(e, streamOut)))
      // A real response has a body, empty or not, unless its status forbids one.
      return new Response(nullBodyStatus(status) ? null : body, {
        status,
        headers: c.initialResponse?.headers ?? {},
      })
    },
  })
  const client = new Client(transport, transport)
  const method = client[lowerFirst(tc.operation)]
  if (typeof method !== 'function') return [`no ${lowerFirst(tc.operation)} method`]

  const values = (es: StreamEvent[]): unknown[] =>
    es.filter((e) => e.params !== undefined).map((e) => reviveDates(e.params))
  const input = {
    ...(reviveDates(c.initialRequestParams ?? {}) as object),
    ...(streamIn === null ? {} : { [streamIn.member]: once(values(requestEvents)) }),
  }

  let out: Record<string, unknown> = {}
  const received: unknown[] = []
  let threw: unknown = undefined
  try {
    out = ((method.length >= 2 ? await method.call(client, input) : await method.call(client)) ?? {}) as Record<
      string,
      unknown
    >
    if (streamOut !== null) {
      for await (const v of out[streamOut.member] as AsyncIterable<unknown>) received.push(v)
    }
  } catch (e) {
    threw = e ?? new Error('threw undefined')
  }

  if (c.expectation === 'failure') {
    return threw !== undefined ? [] : [`expected a failure, got ${JSON.stringify(asJson(received))}`]
  }
  if (threw !== undefined) return [`threw ${describe(threw)}`]
  if (sent === undefined) return ['no request was sent']

  const problems: string[] = []
  const r = c.initialRequest ?? {}
  problems.push(
    ...checkRequest(
      { id: c.id, method: r.method, uri: r.uri, queryParams: r.queryParams, headers: r.headers },
      sent,
    ),
  )
  if (streamIn !== null) problems.push(...checkEvents('request', sent.body ?? '', requestEvents, streamIn))
  else if (r.body !== undefined) problems.push(...checkRequest({ id: c.id, body: r.body, bodyMediaType: 'application/json' }, sent))

  const envelope = Object.fromEntries(Object.entries(out).filter(([k]) => k !== streamOut?.member))
  const expectedEnvelope = asJson(reviveDates(c.initialResponseParams ?? {}))
  if (!isDeepStrictEqual(asJson(envelope) ?? {}, expectedEnvelope)) {
    problems.push(`output ${JSON.stringify(asJson(envelope))}, expected ${JSON.stringify(expectedEnvelope)}`)
  }
  const expectedValues = asJson(values(responseEvents))
  if (!isDeepStrictEqual(asJson(received), expectedValues)) {
    problems.push(`stream ${JSON.stringify(asJson(received))}, expected ${JSON.stringify(expectedValues)}`)
  }
  return problems
}
