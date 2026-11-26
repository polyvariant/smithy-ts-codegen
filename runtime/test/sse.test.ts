import assert from 'node:assert/strict'
import { test } from 'node:test'
import { decodeSse, encodeSse } from '../dist/sse.js'
import { SseParseError } from '../dist/errors.js'

const bytes = (...parts: string[]): AsyncGenerator<Uint8Array> => {
  const encoder = new TextEncoder()
  return (async function* () {
    for (const part of parts) yield encoder.encode(part)
  })()
}

const values = (...vs: unknown[]): AsyncGenerator<unknown> =>
  (async function* () {
    for (const v of vs) yield v
  })()

const collect = async <T>(source: AsyncIterable<T>): Promise<T[]> => {
  const out: T[] = []
  for await (const element of source) out.push(element)
  return out
}

const text = async (source: AsyncIterable<Uint8Array>): Promise<string> => {
  const decoder = new TextDecoder()
  let out = ''
  for await (const chunk of source) out += decoder.decode(chunk, { stream: true })
  return out + decoder.decode()
}

test('encodes one event per value, then the terminator', async () => {
  const got = await text(encodeSse(values({ a: 1 }, { b: 2 }), '[DONE]'))
  assert.equal(got, 'data: {"a":1}\n\ndata: {"b":2}\n\ndata: [DONE]\n\n')
})

test('encodes no terminator event when there is none', async () => {
  const got = await text(encodeSse(values({ a: 1 })))
  assert.equal(got, 'data: {"a":1}\n\n')
})

test('a stream that fails ends without the terminator', async () => {
  const failing = (async function* () {
    yield { a: 1 }
    throw new Error('boom')
  })()
  const decoder = new TextDecoder()
  let sent = ''
  await assert.rejects(async () => {
    for await (const chunk of encodeSse(failing, '[DONE]')) sent += decoder.decode(chunk)
  }, /boom/)
  assert.equal(sent, 'data: {"a":1}\n\n')
})

test('decodes what it encodes', async () => {
  const sent = [{ a: 1 }, { b: 'x\ny' }]
  const got = await collect(decodeSse(encodeSse(values(...sent), '[DONE]'), 'Op.m', '[DONE]'))
  assert.deepEqual(got, sent)
})

test('skips comments, other fields, keep-alives and the terminator', async () => {
  const body = [
    ': a comment',
    'event: update',
    'id: 1',
    'retry: 1000',
    'data: {"a":1}',
    '',
    ': keep-alive',
    '',
    'data:{"a":2}',
    '',
    'data: [DONE]',
    '',
    '',
  ].join('\n')
  const got = await collect(decodeSse(bytes(body), 'Op.m', '[DONE]'))
  assert.deepEqual(got, [{ a: 1 }, { a: 2 }])
})

test('joins multi-line data with newlines', async () => {
  const got = await collect(decodeSse(bytes('data: {"a":\ndata: 1}\n\n'), 'Op.m'))
  assert.deepEqual(got, [{ a: 1 }])
})

test('accepts CRLF and lone CR line endings, even split across chunks', async () => {
  const got = await collect(
    decodeSse(bytes('data: {"a":1}\r', '\n\r\ndata: {"a":2}\r\r', 'data: {"a":3}\r', '\r'), 'Op.m'),
  )
  assert.deepEqual(got, [{ a: 1 }, { a: 2 }, { a: 3 }])
})

test('reassembles an event split across chunks', async () => {
  const got = await collect(decodeSse(bytes('da', 'ta: {"a"', ':1}\n', '\n'), 'Op.m'))
  assert.deepEqual(got, [{ a: 1 }])
})

test('drops an event cut off before its blank line', async () => {
  const got = await collect(decodeSse(bytes('data: {"a":1}\n\ndata: {"a":2}\n'), 'Op.m'))
  assert.deepEqual(got, [{ a: 1 }])
})

test('ignores a leading byte order mark', async () => {
  const got = await collect(decodeSse(bytes('﻿data: {"a":1}\n\n'), 'Op.m'))
  assert.deepEqual(got, [{ a: 1 }])
})

test('without a terminator, every event is a value', async () => {
  await assert.rejects(
    collect(decodeSse(bytes('data: [DONE]\n\n'), 'Op.m')),
    (err: unknown) => err instanceof SseParseError,
  )
})

test('malformed data throws SseParseError carrying the operation and data', async () => {
  await assert.rejects(
    collect(decodeSse(bytes('data: {"a":1}\n\ndata: not json\n\n'), 'Op.m')),
    (err: unknown) =>
      err instanceof SseParseError && err.operation === 'Op.m' && err.data === 'not json',
  )
})
