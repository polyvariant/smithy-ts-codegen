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

export interface ComplianceCase {
  id: string
  kind: 'request' | 'response'
  service: string
  operation: string
  /** The error shape a response case decodes to, or `null` for the operation's output. */
  error: string | null
  case: HttpTestCase
}

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
  const c = tc.case

  let sent: { url: string; init: RequestInit } | undefined
  const reply = (): Response =>
    tc.kind === 'response'
      ? new Response(nullBodyStatus(c.code ?? 200) || !c.body ? null : c.body, {
          status: c.code ?? 200,
          headers: c.headers ?? {},
        })
      : new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } })
  const transport = fetchTransport({
    baseUrl: c.host === undefined ? '' : `https://${c.host}`,
    unauthenticated: false,
    fetch: async (url, init) => {
      sent = { url: String(url), init: init ?? {} }
      return reply()
    },
  })
  const call = operationOf(gen, tc, transport)
  if (typeof call === 'string') return [call]

  if (tc.kind === 'request') {
    try {
      await call(reviveDates(c.params ?? {}))
    } catch (e) {
      // The response is a stub; only a throw before anything was sent matters.
      if (sent === undefined) return [`threw before sending: ${describe(e)}`]
    }
    if (sent === undefined) return ['no request was sent']
    return checkRequest(c, sent)
  }
  return checkResponse(gen, tc, () => call({}))
}

const checkRequest = (c: HttpTestCase, sent: { url: string; init: RequestInit }): string[] => {
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
    const actual = sent.init.body == null ? '' : String(sent.init.body)
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
  tc: ComplianceCase,
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
