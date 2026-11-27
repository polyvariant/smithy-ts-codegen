// The compliance cases against a real smithy4s server (see src/roundtrip.ts).
// Not part of `pnpm run test`: it needs the servers that `sbt complianceRoundTrip`
// starts, which pass their URLs in as ROUNDTRIP_<SUITE>_URL.

import { roundTripSkip, runRoundTrip } from '../src/roundtrip.ts'
import { registerSuites, suites } from '../src/suites.ts'

const urlFor = (suite: string): string => {
  const name = `ROUNDTRIP_${suite.toUpperCase()}_URL`
  const url = process.env[name]
  if (url === undefined) throw new Error(`${name} is not set — run this through \`sbt complianceRoundTrip\``)
  return url
}

await registerSuites(
  new URL('./known-failures.txt', import.meta.url),
  (gen, tc, suite) => runRoundTrip(gen, tc, urlFor(suite)),
  suites,
  roundTripSkip,
)
