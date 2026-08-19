import type {
  StreamTransport,
  StreamTransportRequest,
  StreamTransportResponse,
  Transport,
  TransportRequest,
  TransportResponse,
} from './types.js'

/** Wraps a transport, returning a new one. Compose with {@link chain}. */
export type Middleware = (inner: Transport) => Transport
export type StreamMiddleware = (inner: StreamTransport) => StreamTransport

/** Apply middleware left-to-right, so the first entry is the outermost layer
 * and sees the request first:
 *
 * ```ts
 * chain(transport, tracing, retry)   // tracing wraps retry wraps transport
 * ```
 */
export const chain = (base: Transport, ...layers: Middleware[]): Transport =>
  layers.reduceRight((inner, layer) => layer(inner), base)

export const chainStream = (
  base: StreamTransport,
  ...layers: StreamMiddleware[]
): StreamTransport => layers.reduceRight((inner, layer) => layer(inner), base)

/** Middleware from a function that wraps the call. The building block for
 * tracing, logging, timing — anything that needs the operation name and the
 * chance to run code around the request:
 *
 * ```ts
 * const tracing = around((req, next) => withSpan(req.operation, next))
 * ```
 */
export const around =
  (
    f: (req: TransportRequest, next: () => Promise<TransportResponse>) => Promise<TransportResponse>,
  ): Middleware =>
  (inner) => ({
    request: (req) => f(req, () => inner.request(req)),
  })

export const aroundStream =
  (
    f: (
      req: StreamTransportRequest,
      next: () => Promise<StreamTransportResponse>,
    ) => Promise<StreamTransportResponse>,
  ): StreamMiddleware =>
  (inner) => ({
    requestStream: (req) => f(req, () => inner.requestStream(req)),
  })

/** Rewrite each request before it goes out — add auth headers, stamp a
 * correlation id, rewrite the URL. `f` may be async (e.g. to await a token). */
export const mapRequest =
  (f: (req: TransportRequest) => TransportRequest | Promise<TransportRequest>): Middleware =>
  (inner) => ({
    request: async (req) => inner.request(await f(req)),
  })

/** Merge headers into every request. Header names should be lowercase.
 * `headers` is re-evaluated per request when it's a function, so a rotating
 * token is picked up without rebuilding the transport. */
export const withHeaders = (
  headers: Record<string, string> | (() => Record<string, string> | Promise<Record<string, string>>),
): Middleware =>
  mapRequest(async (req) => ({
    ...req,
    // Per-request headers win: they come from the operation's `@httpHeader`
    // members, which are part of the contract.
    headers: { ...(typeof headers === 'function' ? await headers() : headers), ...(req.headers ?? {}) },
  }))

/** Observe every outcome without changing it — the fetch-side equivalent of an
 * a response interceptor. `onResponse` sees *every* response including
 * non-2xx (this layer never converts a status into a rejection), and `onError`
 * sees genuine failures: network errors, and anything a lower layer threw
 * (e.g. `UnauthenticatedError`). Both are re-thrown/returned unchanged.
 */
export const tap = (handlers: {
  onRequest?: (req: TransportRequest) => void
  onResponse?: (res: TransportResponse, req: TransportRequest) => void
  onError?: (err: unknown, req: TransportRequest) => void
}): Middleware =>
  around(async (req, next) => {
    handlers.onRequest?.(req)
    try {
      const res = await next()
      handlers.onResponse?.(res, req)
      return res
    } catch (err) {
      handlers.onError?.(err, req)
      throw err
    }
  })

/** A mutable stack of `tap` handlers that can be added and removed after the
 * transport is built — the register/unregister pair a response-interceptor
 * registry provides, and what a React effect needs when the handler closes
 * over component state.
 *
 * ```ts
 * const interceptors = interceptorStack()
 * const transport = chain(fetchTransport(...), interceptors.middleware)
 *
 * useEffect(() => interceptors.use({ onError: err => setPopup(err) }), [])
 * ```
 *
 * `use` returns its own remover, so it can be returned directly as an effect
 * cleanup. Handlers run in registration order.
 */
export const interceptorStack = (): {
  middleware: Middleware
  use: (handlers: {
    onRequest?: (req: TransportRequest) => void
    onResponse?: (res: TransportResponse, req: TransportRequest) => void
    onError?: (err: unknown, req: TransportRequest) => void
  }) => () => void
} => {
  type Handlers = {
    onRequest?: (req: TransportRequest) => void
    onResponse?: (res: TransportResponse, req: TransportRequest) => void
    onError?: (err: unknown, req: TransportRequest) => void
  }
  const registered = new Set<Handlers>()

  return {
    middleware: around(async (req, next) => {
      // Snapshot: a handler that ejects itself mid-flight shouldn't perturb
      // the iteration for this request.
      const current = [...registered]
      for (const h of current) h.onRequest?.(req)
      try {
        const res = await next()
        for (const h of current) h.onResponse?.(res, req)
        return res
      } catch (err) {
        for (const h of current) h.onError?.(err, req)
        throw err
      }
    }),
    use: (handlers) => {
      registered.add(handlers)
      return () => {
        registered.delete(handlers)
      }
    },
  }
}
