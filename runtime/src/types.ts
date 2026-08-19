/** The transport contract the generated clients are abstract over.
 *
 * These declarations are structural copies of what `smithy-ts-codegen` emits
 * into every `generated.ts`. They are deliberately *not* imported from the
 * generated file: this package has no idea which model you generated from, and
 * a generated file stays self-contained. TypeScript matches them by shape, so
 * a transport built here satisfies the generated `Transport` interface without
 * either side knowing about the other.
 *
 * Keep them in sync with the codegen's emitted transport block.
 */

/** Per-operation transport options, threaded straight through by the generated
 * client without inspection. Concrete transports and middleware read whatever
 * keys they define (e.g. a `skipErrorPopup` flag read by an error-reporting
 * middleware). */
export interface TransportOptions {
  [key: string]: unknown
}

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export interface TransportRequest {
  /** `<ServiceClient>.<methodName>` — stable per operation. Middleware can use
   * it to name a span; the HTTP layer ignores it. */
  operation: string
  method: HttpMethod
  url: string
  query?: Record<string, string | number | boolean | undefined>
  headers?: Record<string, string>
  body?: unknown
  options?: TransportOptions
}

export interface TransportResponse {
  status: number
  body: unknown
  /** Lowercased header names → values: the generated client's error dispatch
   * looks up `x-error-type` in lowercase. */
  headers: Record<string, string>
}

export interface Transport {
  request(req: TransportRequest): Promise<TransportResponse>
}

/** How a streaming direction is framed on the wire. Derived by the generator
 * from the shape of the `@streaming` member, so a transport never guesses:
 *
 *   - `'ndjson'` — one JSON value per line (`application/x-ndjson`).
 *   - `'binary'` — the body verbatim (`application/octet-stream`), as
 *     `Uint8Array` chunks whose boundaries carry no meaning.
 */
export type StreamEncoding = 'ndjson' | 'binary'

export interface StreamTransportRequest extends TransportRequest {
  /** Framing for the request body when it streams; `undefined` means an
   * ordinary JSON body carried by `body`. */
  requestStreamEncoding?: StreamEncoding
  /** Framing the response body is expected in when it streams; `undefined`
   * means an ordinary JSON response body. */
  responseStreamEncoding?: StreamEncoding
  /** The outgoing stream, present exactly when `requestStreamEncoding` is.
   * Overrides `body`. */
  stream?: AsyncIterable<unknown>
}

export interface StreamTransportResponse {
  status: number
  headers: Record<string, string>
  /** The deframed response body — present exactly when the operation streams
   * its output and the status was 2xx. Already `JSON.parse`d for `'ndjson'`,
   * raw `Uint8Array` chunks for `'binary'`. The generated client validates
   * ndjson elements against the operation's schema. */
  stream?: AsyncIterable<unknown>
  /** The fully-read body, for a non-2xx response (so declared errors can be
   * parsed and thrown) or an operation with a unary response body. */
  body?: unknown
}

export interface StreamTransport {
  requestStream(req: StreamTransportRequest): Promise<StreamTransportResponse>
}

/** A transport that serves both unary and streaming operations. The generated
 * client takes whichever halves it needs, so one object satisfying this can be
 * passed to every client in a model. */
export interface FullTransport extends Transport, StreamTransport {}
