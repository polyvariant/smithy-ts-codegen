import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  asyncIterableToReadable,
  collectBytes,
  decodeNdjson,
  encodeNdjson,
  readableToAsyncIterable,
} from '../dist/ndjson.js'
import { NdjsonParseError } from '../dist/errors.js'

const bytes = (...parts: string[]): AsyncGenerator<Uint8Array> => {
  const encoder = new TextEncoder()
  return (async function* () {
    for (const part of parts) yield encoder.encode(part)
  })()
}

const collect = async <T>(source: AsyncIterable<T>): Promise<T[]> => {
  const out: T[] = []
  for await (const element of source) out.push(element)
  return out
}

test('decodes one value per line', async () => {
  const got = await collect(decodeNdjson(bytes('{"a":1}\n{"a":2}\n'), 'Op.m'))
  assert.deepEqual(got, [{ a: 1 }, { a: 2 }])
})

test('reassembles a value split across chunks', async () => {
  const got = await collect(decodeNdjson(bytes('{"a"', ':1}\n{"b":', '2}\n'), 'Op.m'))
  assert.deepEqual(got, [{ a: 1 }, { b: 2 }])
})

test('emits a final line with no trailing newline', async () => {
  const got = await collect(decodeNdjson(bytes('{"a":1}\n{"a":2}'), 'Op.m'))
  assert.deepEqual(got, [{ a: 1 }, { a: 2 }])
})

test('skips blank lines and tolerates CRLF', async () => {
  const got = await collect(decodeNdjson(bytes('{"a":1}\r\n\r\n{"a":2}\r\n'), 'Op.m'))
  assert.deepEqual(got, [{ a: 1 }, { a: 2 }])
})

test('reassembles a multi-byte character split across chunks', async () => {
  // 'ł' is two bytes; cut between them.
  const encoded = new TextEncoder().encode('{"a":"ł"}\n')
  const source = (async function* () {
    yield encoded.slice(0, 7)
    yield encoded.slice(7)
  })()
  assert.deepEqual(await collect(decodeNdjson(source, 'Op.m')), [{ a: 'ł' }])
})

test('a malformed line throws at that line, after earlier ones were yielded', async () => {
  const iterator = decodeNdjson(bytes('{"a":1}\nnot json\n'), 'Op.m')[Symbol.asyncIterator]()
  assert.deepEqual((await iterator.next()).value, { a: 1 })
  await assert.rejects(() => iterator.next(), NdjsonParseError)
})

test('decoding is lazy — nothing is read before the first pull', async () => {
  let pulled = 0
  const source = (async function* () {
    const encoder = new TextEncoder()
    for (const line of ['{"a":1}\n', '{"a":2}\n']) {
      pulled++
      yield encoder.encode(line)
    }
  })()
  const iterator = decodeNdjson(source, 'Op.m')[Symbol.asyncIterator]()
  assert.equal(pulled, 0)
  await iterator.next()
  assert.equal(pulled, 1)
})

test('encode is the inverse of decode', async () => {
  const values = [{ a: 1 }, { b: 'two' }, { c: [3] }]
  const round = await collect(
    decodeNdjson(
      encodeNdjson(
        (async function* () {
          for (const v of values) yield v
        })(),
      ),
      'Op.m',
    ),
  )
  assert.deepEqual(round, values)
})

test('ReadableStream round-trips through both adapters', async () => {
  const source = bytes('a', 'bc')
  const back = await collectBytes(readableToAsyncIterable(asyncIterableToReadable(source)))
  assert.equal(new TextDecoder().decode(back), 'abc')
})
