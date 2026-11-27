// Runs one compliance case against a real smithy4s server: the generated client
// talks over HTTP to the suite's services, served by smithy4s's own
// simpleRestJson implementation, or smithy4s-ndjson's for the ndjsonRestJson
// suite (see compliance-roundtrip/).
//
//   request cases   the server decodes the client's request with smithy4s and
//                   compares the result with the case's `params` (ndjson: with
//                   its own reading of the case's request).
//   response cases  the server answers with the case's `params`, encoded by
//                   smithy4s, and the client's decoding is compared with them.
//   event cases     both at once: the server compares the request and its
//                   stream with its reading of the case's own request, and
//                   answers with the case's values, encoded by smithy4s-ndjson.
//
// So what is checked is agreement with smithy4s rather than with the letter of
// each test: a disagreement here is a request smithy4s would reject or
// misread, or a response we would misread.

import { fetchTransport } from '@polyvariant/smithy-ts-runtime'
import {
  type ComplianceCase,
  type GeneratedModule,
  callEventCase,
  checkEventOutcome,
  checkResponse,
  describe,
  operationOf,
  readByteStream,
  reviveDates,
  withByteStream,
} from './harness.ts'

type RoundTripResult =
  | { status: 'ok' }
  | { status: 'mismatch'; message: string }
  | { status: 'not-invoked' }

/** A thrown error, with the body of an unexpected response when there is one — that is where
 * smithy4s says why it rejected a request. */
const describeFailure = (e: unknown): string => {
  const body = (e as { body?: unknown } | undefined)?.body
  return body === undefined ? describe(e) : `${describe(e)} ${JSON.stringify(body)}`
}

export const runRoundTrip = async (
  gen: GeneratedModule,
  tc: ComplianceCase,
  baseUrl: string,
): Promise<string[]> => {
  const error = tc.error === null ? '' : `?error=${encodeURIComponent(tc.error)}`
  const armed = await fetch(
    `${baseUrl}/__roundtrip/arm/${tc.kind}/${encodeURIComponent(tc.id)}${error}`,
    { method: 'POST' },
  )
  if (!armed.ok) return [`the server could not set up the case: ${armed.status} ${await armed.text()}`]

  const transport = fetchTransport({ baseUrl, unauthenticated: false })

  if (tc.kind === 'events') {
    // The server answers with the case's values, encoded by smithy4s-ndjson, and records what it
    // decoded from the request: both ends are checked.
    const outcome = await callEventCase(gen, tc, transport)
    if (typeof outcome === 'string') return [outcome]
    const problems = checkEventOutcome(tc, outcome)
    const request = await serverResult(baseUrl, outcome.threw)
    return [...problems, ...request]
  }

  const call = operationOf(gen, tc, transport)
  if (typeof call === 'string') return [call]

  if (tc.kind === 'response') {
    // The server answers whatever is sent, so the input doesn't matter.
    return checkResponse(gen, tc, async () => readByteStream(await call({}), tc.streams?.output))
  }

  let thrown: unknown
  try {
    await call(withByteStream(reviveDates(tc.case.params ?? {}), tc.streams?.input))
  } catch (e) {
    // Expected: the handler records what it decoded, then fails the request on purpose.
    thrown = e
  }
  return serverResult(baseUrl, thrown)
}

/** What the server made of the last request. */
const serverResult = async (baseUrl: string, thrown: unknown): Promise<string[]> => {
  const result = (await (await fetch(`${baseUrl}/__roundtrip/result`)).json()) as RoundTripResult
  switch (result.status) {
    case 'ok':
      return []
    case 'mismatch':
      return [result.message]
    case 'not-invoked':
      return [`smithy4s did not get as far as the handler: ${describeFailure(thrown)}`]
  }
}

/** A case a real server can't take part in: one whose client must reject what it reads. A
 * smithy4s server only writes what it can encode, so the bytes such a case is about never reach
 * the client — the offline suite checks those against the case's own bytes. */
export const roundTripSkip = (tc: ComplianceCase): string | undefined =>
  tc.kind === 'events' && tc.case.expectation === 'failure'
    ? 'the client must reject bytes a smithy4s server never writes'
    : undefined
