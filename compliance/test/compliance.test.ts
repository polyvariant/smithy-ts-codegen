// One node:test test per compliance case, across every suite under generated/.
//
// A case listed in known-failures.txt is expected to fail: it is reported as a
// TODO with what didn't match, and the run stays green. A listed case that
// passes fails the run, as does a listed case that no longer exists — so the
// list can only shrink, and always says exactly what is unsupported.

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { type ComplianceCase, type GeneratedModule, runCase } from '../src/harness.ts'

const root = new URL('../', import.meta.url)
const suites = ['alloy', 'restJson1']

/** `<suite>/<kind>/<id>` per line; `#` starts a comment. */
const knownFailures = new Set(
  readFileSync(new URL('known-failures.txt', root), 'utf8')
    .split('\n')
    .map((line) => line.replace(/#.*/, '').trim())
    .filter((line) => line !== ''),
)
const seen = new Set<string>()

for (const suite of suites) {
  const gen = (await import(new URL(`generated/${suite}/generated.ts`, root).href)) as GeneratedModule
  const cases = JSON.parse(
    readFileSync(new URL(`generated/${suite}/cases.json`, root), 'utf8'),
  ) as ComplianceCase[]

  for (const tc of cases) {
    const key = `${suite}/${tc.kind}/${tc.id}`
    seen.add(key)
    test(key, async (t) => {
      const problems = await runCase(gen, tc)
      if (!knownFailures.has(key)) {
        assert.deepEqual(problems, [])
      } else if (problems.length === 0) {
        assert.fail(`${key} passes now — remove it from known-failures.txt`)
      } else {
        t.todo(problems.join('; '))
      }
    })
  }
}

test('known-failures.txt only lists existing cases', () => {
  assert.deepEqual([...knownFailures].filter((k) => !seen.has(k)), [])
})
