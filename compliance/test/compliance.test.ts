// The compliance cases, against a `fetch` double: each request is compared with
// what the case expects on the wire, each response is the case's own.
// See src/suites.ts for how known-failures.txt is enforced.

import { runCase } from '../src/harness.ts'
import { registerSuites } from '../src/suites.ts'

await registerSuites(new URL('../known-failures.txt', import.meta.url), (gen, tc) => runCase(gen, tc))
