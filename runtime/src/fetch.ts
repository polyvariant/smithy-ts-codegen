import { StreamRequestError, UnauthenticatedError } from './errors.js'
import { parseLossless, stringifyLossless } from './json.js'
import {
  asyncIterableToReadable,
  collectBytes,
  decodeNdjson,
  encodeBinary,
  encodeNdjson,
  readableToAsyncIterable,
} from './ndjson.js'
import type {
  FullTransport,
  StreamTransportRequest,
  StreamTransportResponse,
  TransportRequest,
  TransportResponse,
} from './types.js'

export interface FetchTransportOptions {
  /** Prefixed to every request URL. The generated `url` is an absolute path
   * (`/people/{id}` after label substitution), so this is joined as a prefix,
   * not resolved as a URL — `'/api'` and `'https://host/api'` both work.
   * Defaults to `''` (same origin, paths as generated). */
  baseUrl?: string
  /** Passed straight to `fetch`. Defaults to `'include'` so an HttpOnly session
   * cookie rides along, which is what every same-origin SPA setup wants. Use
   * `'omit'` for a token-authenticated API. */
  credentials?: RequestCredentials
  /** Merged into every request; per-request headers (from `@httpHeader`
   * members) win. Re-evaluated per request when a function. */
  headers?: Record<string, string> | (() => Record<string, string> | Promise<Record<string, string>>)
  /** Override the `fetch` implementation — a test double, or a Node/undici
   * instance. Defaults to the global `fetch` (looked up per call, so patching
   * the global after construction still works). */
  fetch?: typeof globalThis.fetch
  /** Escape hatch for anything else `fetch` takes (`mode`, `cache`, `signal`,
   * `redirect`). Applied before the fields this transport controls. */
  init?: Omit<RequestInit, 'method' | 'body' | 'headers'>
  /** Turn a 401 into a thrown error rather than returning it as a response.
   *
   * The auth middleware sits in front of every route, so 401 is never in an
   * operation's `errors: [...]` and the generated client can't dispatch it —
   * it would surface as `UnexpectedResponseError`. Defaults to `true`,
   * throwing this package's {@link UnauthenticatedError}.
   *
   * Pass your generated `UnauthenticatedError` class here to keep call sites'
   * `instanceof` checks against the generated one working:
   * `unauthenticated: op => new GeneratedUnauthenticatedError(op)`.
   * Pass `false` to leave 401s alone. */
  unauthenticated?: boolean | ((operation: string) => unknown)
}

const joinUrl = (baseUrl: string, url: string): string => {
  if (baseUrl === '') return url
  const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl
  return url.startsWith('/') ? base + url : base + '/' + url
}

/** Percent-encodes everything outside RFC 3986's unreserved set, as the protocol
 * requires — `URLSearchParams` would write a space as `+` and leave `*` bare. */
const encodeQueryPart = (value: string): string =>
  encodeURIComponent(value).replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase())

/** `url` with the defined query entries appended. Booleans and numbers are
 * stringified; a list repeats the parameter once per element; `undefined`
 * members are dropped rather than sent as the string `"undefined"`. The
 * generated `url` may already carry a query — the constant parameters of an
 * `@http(uri: "/path?flag")` — so this appends to it rather than starting one. */
const withQuery = (url: string, query: TransportRequest['query']): string => {
  if (query === undefined) return url
  const parts: string[] = []
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined) continue
    const values: readonly (string | number | boolean)[] = Array.isArray(value)
      ? value
      : [value as string | number | boolean]
    for (const v of values) parts.push(encodeQueryPart(key) + '=' + encodeQueryPart(String(v)))
  }
  if (parts.length === 0) return url
  return url + (url.includes('?') ? '&' : '?') + parts.join('&')
}

/** Lowercase the header names, as `TransportResponse.headers` promises — the
 * generated client's `x-error-type` lookup depends on it. */
const collectHeaders = (headers: Headers): Record<string, string> => {
  const out: Record<string, string> = {}
  headers.forEach((value, key) => {
    out[key.toLowerCase()] = value
  })
  return out
}

/** Parse a response body as JSON, tolerating an empty one (204, or an
 * operation with no output members). A non-JSON error body (an HTML error page
 * from a proxy) is surfaced as the raw text rather than throwing, so the
 * status still reaches the caller. */
const readBody = async (res: Response): Promise<unknown> => {
  const text = await res.text()
  if (text.length === 0) return undefined
  try {
    return parseLossless(text)
  } catch {
    return text
  }
}

const resolveHeaders = async (
  headers: FetchTransportOptions['headers'],
): Promise<Record<string, string>> => {
  if (headers === undefined) return {}
  return typeof headers === 'function' ? await headers() : headers
}

const checkUnauthenticated = (
  res: { status: number },
  operation: string,
  unauthenticated: FetchTransportOptions['unauthenticated'],
): void => {
  if (res.status !== 401 || unauthenticated === false) return
  if (typeof unauthenticated === 'function') throw unauthenticated(operation)
  throw new UnauthenticatedError(operation)
}

/** A `fetch`-backed transport implementing both the unary and the streaming
 * halves of the generated contract.
 *
 * ```ts
 * const transport = fetchTransport({ baseUrl: '/api' })
 * const directory = new DirectoryClient(transport)
 * const feed = new FeedClient(transport, transport)   // streaming too
 * ```
 *
 * It never converts a non-2xx status into a rejection: the generated client
 * needs the status and body to dispatch the operation's declared errors. The
 * one exception is 401 (see `unauthenticated`).
 */
export const fetchTransport = (options: FetchTransportOptions = {}): FullTransport => {
  const {
    baseUrl = '',
    credentials = 'include',
    headers: defaultHeaders,
    init = {},
    unauthenticated = true,
  } = options
  const doFetch: typeof globalThis.fetch = (input, requestInit) =>
    (options.fetch ?? globalThis.fetch)(input, requestInit)

  const request = async (req: TransportRequest): Promise<TransportResponse> => {
    const res = await doFetch(withQuery(joinUrl(baseUrl, req.url), req.query), {
      ...init,
      method: req.method,
      credentials,
      headers: {
        ...(await resolveHeaders(defaultHeaders)),
        ...(req.body !== undefined ? { 'content-type': 'application/json' } : {}),
        ...(req.headers ?? {}),
      },
      // Spread rather than assign `undefined`: with
      // `exactOptionalPropertyTypes`, `body: undefined` is not a valid
      // `RequestInit` (and a GET must have no body at all).
      ...(req.body !== undefined ? { body: stringifyLossless(req.body) } : {}),
    })

    checkUnauthenticated(res, req.operation, unauthenticated)

    return { status: res.status, body: await readBody(res), headers: collectHeaders(res.headers) }
  }

  const requestStream = async (req: StreamTransportRequest): Promise<StreamTransportResponse> => {
    const outgoing = await streamBody(req)

    const res = await doFetch(withQuery(joinUrl(baseUrl, req.url), req.query), {
      ...init,
      method: req.method,
      credentials,
      headers: {
        ...(await resolveHeaders(defaultHeaders)),
        ...requestContentType(req),
        // Tell the server which framing we want back, so it doesn't have to
        // infer it from the operation alone.
        ...responseAccept(req),
        ...(req.headers ?? {}),
      },
      ...(outgoing.body !== undefined ? { body: outgoing.body } : {}),
      // Required by Chromium to stream a request body at all; harmless
      // elsewhere, and absent from the DOM lib types, hence the cast.
      ...(outgoing.duplex ? ({ duplex: 'half' } as RequestInit) : {}),
    })

    checkUnauthenticated(res, req.operation, unauthenticated)

    const headers = collectHeaders(res.headers)

    // A streamed response commits its status before the first element, so
    // anything non-2xx here failed *before* the stream began: read the body so
    // the generated client can dispatch a declared error against it.
    if (!res.ok || req.responseStreamEncoding === undefined) {
      return { status: res.status, headers, body: await readBody(res) }
    }

    if (res.body === null) {
      throw new StreamRequestError(req.operation, res.status, undefined)
    }

    const bytes = readableToAsyncIterable(res.body)
    return {
      status: res.status,
      headers,
      stream:
        req.responseStreamEncoding === 'ndjson' ? decodeNdjson(bytes, req.operation) : bytes,
    }
  }

  return { request, requestStream }
}

const requestContentType = (req: StreamTransportRequest): Record<string, string> => {
  switch (req.requestStreamEncoding) {
    case 'ndjson':
      return { 'content-type': 'application/x-ndjson' }
    case 'binary':
      return { 'content-type': 'application/octet-stream' }
    default:
      return req.body !== undefined ? { 'content-type': 'application/json' } : {}
  }
}

const responseAccept = (req: StreamTransportRequest): Record<string, string> => {
  switch (req.responseStreamEncoding) {
    case 'ndjson':
      return { accept: 'application/x-ndjson' }
    case 'binary':
      return { accept: 'application/octet-stream' }
    default:
      return {}
  }
}

/** Build the `fetch` body for a (possibly streaming) request.
 *
 * A streamed request body needs `duplex: 'half'` and is only supported over
 * HTTP/2 in Chromium; Firefox and Safari don't support it at all. Rather than
 * fail there, we buffer the stream into a single body — correct, just not
 * incremental. `streamRequests` is decided once per call via feature
 * detection.
 */
const streamBody = async (
  req: StreamTransportRequest,
): Promise<{ body: BodyInit | undefined; duplex: boolean }> => {
  if (req.requestStreamEncoding === undefined || req.stream === undefined) {
    return {
      body: req.body !== undefined ? stringifyLossless(req.body) : undefined,
      duplex: false,
    }
  }

  const framed =
    req.requestStreamEncoding === 'ndjson' ? encodeNdjson(req.stream) : encodeBinary(req.stream)

  if (supportsRequestStreams()) {
    return { body: asyncIterableToReadable(framed), duplex: true }
  }
  const buffered = await collectBytes(framed)
  return { body: buffered as unknown as BodyInit, duplex: false }
}

/** Whether this platform can send a `ReadableStream` as a request body.
 * Detected the way the platform docs prescribe: constructing a `Request` with a
 * stream body throws (or never reads `duplex`) where it isn't supported. The
 * result is cached — it can't change within a page. */
let requestStreamSupport: boolean | undefined

const supportsRequestStreams = (): boolean => {
  if (requestStreamSupport !== undefined) return requestStreamSupport
  if (typeof Request === 'undefined' || typeof ReadableStream === 'undefined') {
    requestStreamSupport = false
    return false
  }
  let duplexAccessed = false
  try {
    const probe = new Request('https://example.invalid', {
      method: 'POST',
      body: new ReadableStream(),
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      get duplex() {
        duplexAccessed = true
        return 'half'
      },
    } as RequestInit)
    // If `duplex` was read and no content-type was inferred, streaming is live.
    requestStreamSupport = duplexAccessed && !probe.headers.has('content-type')
  } catch {
    requestStreamSupport = false
  }
  return requestStreamSupport
}
