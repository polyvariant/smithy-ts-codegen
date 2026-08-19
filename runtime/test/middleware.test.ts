import assert from 'node:assert/strict'
import { test } from 'node:test'
import { around, chain, interceptorStack, mapRequest, tap, withHeaders } from '../dist/middleware.js'
import type { Transport, TransportRequest, TransportResponse } from '../dist/types.js'

const ok = (body: unknown = {}): TransportResponse => ({ status: 200, body, headers: {} })

const recording = (): { transport: Transport; seen: TransportRequest[] } => {
  const seen: TransportRequest[] = []
  return {
    seen,
    transport: {
      request: async (req) => {
        seen.push(req)
        return ok()
      },
    },
  }
}

const req = (over: Partial<TransportRequest> = {}): TransportRequest => ({
  operation: 'S.op',
  method: 'GET',
  url: '/x',
  ...over,
})

test('chain applies layers outermost-first', async () => {
  const order: string[] = []
  const layer = (name: string) =>
    around(async (_r, next) => {
      order.push('enter ' + name)
      const res = await next()
      order.push('exit ' + name)
      return res
    })
  const base = recording()
  await chain(base.transport, layer('a'), layer('b')).request(req())
  assert.deepEqual(order, ['enter a', 'enter b', 'exit b', 'exit a'])
})

test('withHeaders merges defaults but per-request headers win', async () => {
  const base = recording()
  const t = chain(base.transport, withHeaders({ authorization: 'Bearer t', 'x-a': '1' }))
  await t.request(req({ headers: { authorization: 'Bearer override' } }))
  assert.deepEqual(base.seen[0]!.headers, { authorization: 'Bearer override', 'x-a': '1' })
})

test('withHeaders re-evaluates a function per request', async () => {
  let token = 'first'
  const base = recording()
  const t = chain(base.transport, withHeaders(() => ({ authorization: token })))
  await t.request(req())
  token = 'second'
  await t.request(req())
  assert.equal(base.seen[0]!.headers!['authorization'], 'first')
  assert.equal(base.seen[1]!.headers!['authorization'], 'second')
})

test('mapRequest can rewrite the url', async () => {
  const base = recording()
  await chain(base.transport, mapRequest((r) => ({ ...r, url: '/v2' + r.url }))).request(req())
  assert.equal(base.seen[0]!.url, '/v2/x')
})

test('tap observes non-2xx responses without converting them to errors', async () => {
  const seen: number[] = []
  const base: Transport = { request: async () => ({ status: 500, body: {}, headers: {} }) }
  const res = await chain(base, tap({ onResponse: (r) => seen.push(r.status) })).request(req())
  assert.deepEqual(seen, [500])
  assert.equal(res.status, 500)
})

test('tap observes a thrown error and rethrows it', async () => {
  const boom = new Error('network')
  const base: Transport = {
    request: () => Promise.reject(boom),
  }
  const seen: unknown[] = []
  await assert.rejects(
    () => chain(base, tap({ onError: (err) => seen.push(err) })).request(req()),
    (err) => err === boom,
  )
  assert.deepEqual(seen, [boom])
})

test('tap sees the per-operation options blob the codegen threads through', async () => {
  const base = recording()
  let skipped: unknown
  await chain(
    base.transport,
    tap({ onRequest: (r) => (skipped = r.options?.['skipErrorPopup']) }),
  ).request(req({ options: { skipErrorPopup: true } }))
  assert.equal(skipped, true)
})

test('interceptorStack handlers fire, and stop after their remover runs', async () => {
  const base = recording()
  const interceptors = interceptorStack()
  const t = chain(base.transport, interceptors.middleware)

  const seen: string[] = []
  const remove = interceptors.use({ onResponse: () => seen.push('hit') })
  await t.request(req())
  remove()
  await t.request(req())
  assert.deepEqual(seen, ['hit'])
})

test('interceptorStack runs handlers in registration order', async () => {
  const base = recording()
  const interceptors = interceptorStack()
  const t = chain(base.transport, interceptors.middleware)
  const seen: string[] = []
  interceptors.use({ onResponse: () => seen.push('first') })
  interceptors.use({ onResponse: () => seen.push('second') })
  await t.request(req())
  assert.deepEqual(seen, ['first', 'second'])
})

test('a handler ejecting itself mid-request does not disturb that request', async () => {
  const base = recording()
  const interceptors = interceptorStack()
  const t = chain(base.transport, interceptors.middleware)
  const seen: string[] = []
  const remove = interceptors.use({
    onRequest: () => {
      seen.push('request')
      remove()
    },
    onResponse: () => seen.push('response'),
  })
  await t.request(req())
  assert.deepEqual(seen, ['request', 'response'])
})
