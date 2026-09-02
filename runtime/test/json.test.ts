import assert from 'node:assert/strict'
import { test } from 'node:test'
import { parseLossless, stringifyLossless } from '../dist/json.js'

const LONG_MAX = '9223372036854775807'
const LONG_MIN = '-9223372036854775808'

test('parse keeps a value that exceeds the safe integer range exact', () => {
  const parsed = parseLossless(`{"seed":${LONG_MAX}}`) as { seed: unknown }
  assert.equal(parsed.seed, LONG_MAX)
  // The whole point: this is what the built-in would have done to it.
  assert.notEqual(String(JSON.parse(`{"seed":${LONG_MAX}}`).seed), LONG_MAX)
})

test('parse hands back safe numbers as numbers', () => {
  const parsed = parseLossless('{"n":42,"f":1.5,"neg":-7,"zero":0}') as Record<string, unknown>
  assert.equal(parsed.n, 42)
  assert.equal(parsed.f, 1.5)
  assert.equal(parsed.neg, -7)
  assert.equal(parsed.zero, 0)
  for (const key of Object.keys(parsed)) assert.equal(typeof parsed[key], 'number')
})

test('parse leaves non-numeric values alone', () => {
  const parsed = parseLossless('{"s":"hi","b":true,"nil":null,"arr":[1,"two"],"o":{"x":3}}')
  assert.deepEqual(parsed, { s: 'hi', b: true, nil: null, arr: [1, 'two'], o: { x: 3 } })
})

test('stringify writes a bigint as a bare numeric literal', () => {
  assert.equal(stringifyLossless({ seed: BigInt(LONG_MAX) }), `{"seed":${LONG_MAX}}`)
  assert.equal(stringifyLossless({ seed: BigInt(LONG_MIN) }), `{"seed":${LONG_MIN}}`)
})

test('stringify leaves ordinary values as JSON.stringify would', () => {
  const value = { s: 'hi', n: 42, f: 1.5, b: true, nil: null, arr: [1, 2], o: { x: 3 } }
  assert.equal(stringifyLossless(value), JSON.stringify(value))
})

test('a string that merely looks numeric stays quoted', () => {
  // Only a bigint means "bare literal" — a plain string is still a string, or
  // every numeric-looking string field would change type on the wire.
  assert.equal(stringifyLossless({ id: '123' }), '{"id":"123"}')
})

test('a wide value survives a full round trip', () => {
  const wire = `{"seed":${LONG_MAX},"n":42}`
  const parsed = parseLossless(wire) as { seed: string; n: number }
  const sent = stringifyLossless({ seed: BigInt(parsed.seed), n: parsed.n })
  assert.equal(sent, wire)
})
