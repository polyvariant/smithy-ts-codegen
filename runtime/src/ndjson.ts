import { NdjsonParseError } from './errors.js'

/** Framing for the two encodings the protocol defines. Both directions are
 * handled here so a transport only has to move bytes.
 *
 * These are exported because a transport this package doesn't ship (a
 * WebSocket bridge, a test double, a Node `http` client) still needs exactly
 * this framing to interoperate with the generated clients.
 */

import { parseLossless, stringifyLossless } from './json.js'

/** Serialise values as ndjson: one element per line, each newline-terminated,
 * using the same lossless codec as the unary body so a `@lossless` member
 * survives here too. The reverse of {@link decodeNdjson}. */
export const encodeNdjson = async function* (
  source: AsyncIterable<unknown>,
): AsyncGenerator<Uint8Array, void, undefined> {
  const encoder = new TextEncoder()
  for await (const element of source) {
    yield encoder.encode(stringifyLossless(element) + '\n')
  }
}

/** Pass `Uint8Array` chunks through unchanged. Chunk boundaries carry no
 * meaning in either direction, so binary framing is the identity — it exists
 * so both encodings can be handled uniformly. */
export const encodeBinary = async function* (
  source: AsyncIterable<unknown>,
): AsyncGenerator<Uint8Array, void, undefined> {
  for await (const chunk of source) {
    yield chunk as Uint8Array
  }
}

/** Split a byte stream on newlines and parse each non-empty line losslessly,
 * lazily — one line is decoded per pull, so a long-lived stream never buffers
 * more than the partial line in flight.
 *
 * A line that isn't valid JSON throws {@link NdjsonParseError} at that line
 * rather than truncating the stream silently. Schema validation happens a layer
 * up, in the generated client's `decodeStream`.
 */
export const decodeNdjson = async function* (
  source: AsyncIterable<Uint8Array>,
  operation: string,
): AsyncGenerator<unknown, void, undefined> {
  const decoder = new TextDecoder()
  let buffer = ''

  const parse = (line: string): unknown => {
    try {
      return parseLossless(line)
    } catch (err) {
      throw new NdjsonParseError(operation, line, err)
    }
  }

  for await (const chunk of source) {
    buffer += decoder.decode(chunk, { stream: true })
    let newlineAt = buffer.indexOf('\n')
    while (newlineAt >= 0) {
      const line = buffer.slice(0, newlineAt)
      buffer = buffer.slice(newlineAt + 1)
      // Tolerate CRLF, and skip blank lines (heartbeats often ride as those).
      const trimmed = line.endsWith('\r') ? line.slice(0, -1) : line
      if (trimmed !== '') yield parse(trimmed)
      newlineAt = buffer.indexOf('\n')
    }
  }
  // Flush whatever the decoder held back, then the last unterminated line —
  // a server that ends the body without a trailing newline still delivers it.
  buffer += decoder.decode()
  const rest = buffer.endsWith('\r') ? buffer.slice(0, -1) : buffer
  if (rest !== '') yield parse(rest)
}

/** Read a `ReadableStream<Uint8Array>` (what `fetch` gives you) as an
 * `AsyncIterable`. Node 18+ and Deno make `ReadableStream` itself async
 * iterable, but browsers still don't, so we go through the reader. The reader
 * is released when iteration ends for any reason, including `break`. */
export const readableToAsyncIterable = async function* (
  body: ReadableStream<Uint8Array>,
): AsyncGenerator<Uint8Array, void, undefined> {
  const reader = body.getReader()
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      if (value !== undefined) yield value
    }
  } finally {
    reader.releaseLock()
  }
}

/** Turn an `AsyncIterable<Uint8Array>` into a `ReadableStream` for `fetch`'s
 * `body`. Used only where the platform can't take an async iterable directly. */
export const asyncIterableToReadable = (
  source: AsyncIterable<Uint8Array>,
): ReadableStream<Uint8Array> => {
  const iterator = source[Symbol.asyncIterator]()
  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      const { value, done } = await iterator.next()
      if (done) controller.close()
      else controller.enqueue(value)
    },
    async cancel(reason) {
      await iterator.return?.(reason)
    },
  })
}

/** Concatenate a byte stream into one `Uint8Array` — the fallback for a
 * platform that won't stream a request body (see `duplex` support). */
export const collectBytes = async (source: AsyncIterable<Uint8Array>): Promise<Uint8Array> => {
  const chunks: Uint8Array[] = []
  let total = 0
  for await (const chunk of source) {
    chunks.push(chunk)
    total += chunk.length
  }
  const out = new Uint8Array(total)
  let at = 0
  for (const chunk of chunks) {
    out.set(chunk, at)
    at += chunk.length
  }
  return out
}
