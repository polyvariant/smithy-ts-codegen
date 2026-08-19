/** Errors this package throws. They mirror the ones the codegen emits into
 * `generated.ts`, but are *distinct classes* — an `instanceof` check against
 * the generated `UnauthenticatedError` will not match one thrown from here.
 *
 * That matters at call sites like `catch (e) { if (e instanceof UnauthenticatedError) ... }`.
 * Import the class from this package when you use a transport from this
 * package, or pass your generated class via `unauthenticated` (see
 * `TransportErrors`) to keep the generated one.
 */

/** Thrown by these transports for any 401. The auth middleware sits in front of
 * every route and isn't modelled per-operation, so 401 never appears in an
 * operation's `errors: [...]` and the generated client cannot dispatch it. */
export class UnauthenticatedError extends Error {
  readonly operation: string
  constructor(operation: string) {
    super(operation + ' -> 401 Unauthorized')
    this.name = 'UnauthenticatedError'
    this.operation = operation
  }
}

/** Thrown when a streaming request failed before the stream began — the status
 * was non-2xx and the operation declares no error for it, or the response
 * carried no readable body to deframe. Once a stream has started, its status is
 * already committed; failures after that arrive as elements of the streamed
 * union (the protocol's terminal `completed` / `failed` member). */
export class StreamRequestError extends Error {
  readonly operation: string
  readonly status: number
  readonly body: unknown
  constructor(operation: string, status: number, body: unknown) {
    super(operation + ' -> stream request failed with ' + status)
    this.name = 'StreamRequestError'
    this.operation = operation
    this.status = status
    this.body = body
  }
}

/** Thrown while reading an ndjson response whose line was not valid JSON.
 * Schema validation is the generated client's job (it throws
 * `StreamDecodeError`); this is the framing layer failing earlier. */
export class NdjsonParseError extends Error {
  readonly operation: string
  readonly line: string
  constructor(operation: string, line: string, cause: unknown) {
    super(operation + ' -> could not parse an ndjson line', { cause })
    this.name = 'NdjsonParseError'
    this.operation = operation
    this.line = line
  }
}
