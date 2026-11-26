import { SseParseError } from './errors.js'
import { parseLossless, stringifyLossless } from './json.js'

/** Server-sent-events framing (`text/event-stream`), for a `@streaming` union
 * marked `org.polyvariant.ndjson#serverSentEvents`: one event per value, its
 * `data` being the value's JSON exactly as it would appear on an ndjson line.
 *
 * Exported for the same reason as the ndjson framing: a transport this package
 * doesn't ship still needs exactly this framing to interoperate.
 *
 * The rules match the server (smithy4s-ndjson's `Sse`) and the WHATWG
 * event-stream format, as far as they concern the event's data.
 */

/** Frame one event: a `data` field per line of `data`, then the blank line
 * that dispatches it. Splitting at every line break a reader recognises keeps
 * the event well-formed whatever `data` holds. */
const encodeEvent = (data: string): string =>
  data
    .split(/\r\n|\r|\n/)
    .map((line) => 'data: ' + line + '\n')
    .join('') + '\n'

/** Serialise values as server-sent events, using the same lossless codec as the
 * unary body. After the last value, one more event whose data is `terminator`,
 * if given — written only once `source` completes, so a stream that fails ends
 * without it. The reverse of {@link decodeSse}. */
export const encodeSse = async function* (
  source: AsyncIterable<unknown>,
  terminator?: string,
): AsyncGenerator<Uint8Array, void, undefined> {
  const encoder = new TextEncoder()
  for await (const element of source) {
    yield encoder.encode(encodeEvent(stringifyLossless(element)))
  }
  if (terminator !== undefined) yield encoder.encode(encodeEvent(terminator))
}

/** The value of a `data` field, or `undefined` for any other field. A field's
 * value starts after its colon, minus a single leading space; a line without a
 * colon is a field with an empty value. */
const dataField = (line: string): string | undefined => {
  const colon = line.indexOf(':')
  const name = colon < 0 ? line : line.slice(0, colon)
  if (name !== 'data') return undefined
  if (colon < 0) return ''
  const value = line.slice(colon + 1)
  return value.startsWith(' ') ? value.slice(1) : value
}

/** Split a byte stream into events and parse each one's data losslessly,
 * lazily — one event is decoded per pull.
 *
 * Lines end in `\r\n`, `\r` or `\n`. `data` fields are joined with newlines
 * until a blank line dispatches the event. Comments (lines starting with `:`)
 * and the other fields (`event`, `id`, `retry`) are skipped, as is an event
 * without data (a keep-alive) and the `terminator` event. An event cut off
 * before its blank line is not dispatched.
 *
 * Data that isn't valid JSON throws {@link SseParseError} at that event rather
 * than truncating the stream silently. Schema validation happens a layer up,
 * in the generated client's `decodeStream`.
 */
export const decodeSse = async function* (
  source: AsyncIterable<Uint8Array>,
  operation: string,
  terminator?: string,
): AsyncGenerator<unknown, void, undefined> {
  const decoder = new TextDecoder()
  let buffer = ''
  let data: string[] = []
  let first = true

  /** Handle one complete line; returns the event's data when it dispatches. */
  const onLine = (line: string): string | undefined => {
    if (line === '') {
      const event = data
      data = []
      return event.length > 0 ? event.join('\n') : undefined
    }
    if (line.startsWith(':')) return undefined
    const value = dataField(line)
    if (value !== undefined) data.push(value)
    return undefined
  }

  const parse = (event: string): unknown => {
    try {
      return parseLossless(event)
    } catch (err) {
      throw new SseParseError(operation, event, err)
    }
  }

  for await (const chunk of source) {
    buffer += decoder.decode(chunk, { stream: true })
    if (first && buffer.length > 0) {
      // The format allows a leading byte order mark.
      if (buffer.startsWith('﻿')) buffer = buffer.slice(1)
      first = false
    }
    for (;;) {
      const breakAt = buffer.search(/[\r\n]/)
      if (breakAt < 0) break
      // A `\r` at the end of what has arrived may be half of a `\r\n`: wait
      // for the next chunk before deciding where the line ends.
      if (buffer[breakAt] === '\r' && breakAt === buffer.length - 1) break
      const width = buffer.startsWith('\r\n', breakAt) ? 2 : 1
      const line = buffer.slice(0, breakAt)
      buffer = buffer.slice(breakAt + width)
      const event = onLine(line)
      if (event !== undefined && event !== terminator) yield parse(event)
    }
  }
  // A trailing lone `\r` still ends its line; anything after the last line
  // break belongs to an event that was never dispatched, so it is dropped.
  buffer += decoder.decode()
  if (buffer.endsWith('\r')) {
    const event = onLine(buffer.slice(0, -1))
    if (event !== undefined && event !== terminator) yield parse(event)
  }
}
