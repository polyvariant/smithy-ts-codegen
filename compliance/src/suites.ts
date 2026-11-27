// Registers one node:test test per compliance case, across every suite under
// generated/, checked by `run` against a known-failures list.
//
// A case listed as a known failure is expected to fail: it is reported as a
// TODO with what didn't match, and the run stays green. A listed case that
// passes fails the run, as does a listed case that no longer exists — so the
// list can only shrink, and always says exactly what is unsupported.

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import type { ComplianceCase, GeneratedModule } from './harness.ts'

export const suites = ['alloy', 'restJson1', 'ndjsonRestJson'] as const
export type Suite = (typeof suites)[number]

const root = new URL('../', import.meta.url)

/** `<suite>/<kind>/<id>` per line; `#` starts a comment. */
const readKnownFailures = (file: URL): Set<string> =>
  new Set(
    readFileSync(file, 'utf8')
      .split('\n')
      .map((line) => line.replace(/#.*/, '').trim())
      .filter((line) => line !== ''),
  )

export const registerSuites = async (
  knownFailuresFile: URL,
  run: (gen: GeneratedModule, tc: ComplianceCase, suite: Suite) => Promise<string[]>,
  only: readonly Suite[] = suites,
  /** Why `tc` can't be run here, if it can't: it is registered as skipped, with that reason. */
  skip: (tc: ComplianceCase) => string | undefined = () => undefined,
): Promise<void> => {
  const knownFailures = readKnownFailures(knownFailuresFile)
  const listName = knownFailuresFile.pathname.split('/').slice(-2).join('/')
  const seen = new Set<string>()

  for (const suite of only) {
    const gen = (await import(new URL(`generated/${suite}/generated.ts`, root).href)) as GeneratedModule
    const cases = JSON.parse(
      readFileSync(new URL(`generated/${suite}/cases.json`, root), 'utf8'),
    ) as ComplianceCase[]

    for (const tc of cases) {
      const key = `${suite}/${tc.kind}/${tc.id}`
      seen.add(key)
      const reason = skip(tc)
      if (reason !== undefined) {
        test(key, { skip: reason }, () => {})
        continue
      }
      test(key, async (t) => {
        const problems = await run(gen, tc, suite)
        if (!knownFailures.has(key)) {
          assert.deepEqual(problems, [])
        } else if (problems.length === 0) {
          assert.fail(`${key} passes now — remove it from ${listName}`)
        } else {
          t.todo(problems.join('; '))
        }
      })
    }
  }

  test(`${listName} only lists existing cases`, () => {
    assert.deepEqual([...knownFailures].filter((k) => !seen.has(k)), [])
  })
}
