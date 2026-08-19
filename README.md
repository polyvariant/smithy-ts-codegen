# smithy-ts-codegen

A [smithy-build](https://smithy.io/2.0/guides/building-models/build-config.html) plugin that
turns a Smithy model into a single self-contained TypeScript file: [zod](https://zod.dev)
schemas + types for every reachable shape, typed HTTP clients for your
[`alloy#simpleRestJson`](https://github.com/disneystreaming/alloy) services, and typed mock
stubs for driving those services in Storybook.

## What it emits

From one model, into a single `generated.ts`:

1. **Data types** — a `XxxSchema` (zod) + `type Xxx = z.infer<typeof XxxSchema>` for every
   reachable structure, union, enum, list, map, and simple-type alias (aliases are
   `.brand<'Xxx'>()`-ed for nominal typing).
2. **Error classes** — one `XxxError extends Error` per `@error` shape, carrying the declared
   HTTP status and error-type name.
3. **Transport** — a small `Transport` interface plus request/response types. The generated
   clients are transport-agnostic; a ready-made `fetch` implementation ships separately in
   [`@polyvariant/smithy-ts-runtime`](runtime/). Models with streaming operations also get a
   `StreamTransport` (see [Streaming](#streaming)).
4. **Service clients** — one `XxxClient` class per service, one `async` method per operation.
   Each method walks the operation's `@http` trait to build the request (URI labels, query,
   headers, body) and parses the response with the generated output schema, dispatching declared
   errors by status (and `X-Error-Type` when several errors share a status).
5. **Storybook mock stubs** — a typed `XxxHandlers` interface + `XxxMock` descriptor per
   service, plus a shared `mockService` runtime, so a story can implement just the operations it
   exercises.

## Usage

### As a smithy-build plugin

Add the artifact to your smithy-build classpath and reference the plugin by name (`ts-codegen`):

```json
{
  "version": "1.0",
  "plugins": {
    "ts-codegen": {
      "outFile": "generated.ts",
      "excludeServices": ["myorg.auth#AuthService"]
    }
  }
}
```

Settings:

- `outFile` — destination filename within the plugin's output dir (default `generated.ts`).
- `excludeServices` — fully-qualified service shape ids (`namespace#Name`) to skip. Their
  referenced data shapes are still emitted; only the operations and the client class are
  dropped. Use this for services you hand-roll (streaming, custom routing, …).

### From sbt (recommended)

Add the sbt plugin (`project/plugins.sbt`):

```scala
addSbtPlugin("org.polyvariant" % "sbt-smithy-ts-codegen" % "<version>")
```

Enable it on a project and point it at your smithy sources:

```scala
enablePlugins(SmithyTsCodegenPlugin)

tsCodegenSmithyDirs := Seq(baseDirectory.value / "src" / "main" / "smithy")
tsCodegenOutputFile := baseDirectory.value / "src" / "generated.ts"
tsCodegenExcludeServices := Seq("myorg.auth#AuthService")
```

then run `tsCodegen`. The plugin resolves the `smithy-ts-codegen-cli` artifact (at the plugin's
own version) with coursier and runs it in a forked JVM, so nothing about your project's Scala
version affects the codegen. Settings:

- `tsCodegenSmithyDirs` — dirs scanned for `*.smithy` / `*.json` (default `src/main/smithy`).
- `tsCodegenOutputFile` — where to write the TypeScript (default `target/generated.ts`).
- `tsCodegenExcludeServices` — service shape ids to skip (see above).
- `tsCodegenVersion` — override the codegen version to resolve (defaults to the plugin's own).

### Programmatically (`TsCodegenPlugin.generate`)

The core is a pure function from a loaded `software.amazon.smithy.model.Model` to a `String`:

```scala
import org.polyvariant.smithy.ts.TsCodegenPlugin
import software.amazon.smithy.model.Model

val model: Model = ???
val ts: String = TsCodegenPlugin.generate(model, excludeServices = Set.empty)
```

### From any build (forked JVM)

The `smithy-ts-codegen-cli` artifact's `org.polyvariant.smithy.ts.cli.Main` assembles a model
from `.smithy`/`.json` sources and writes the output file. Run it with the CLI (and its deps) on
the classpath:

```
Main <smithyDirs (path-separator-joined)> <outFile> [<excludeServices (comma-joined)>]
```

This is what the sbt plugin forks; the smithy-build plugin is discovered via the SPI.

## Streaming

Operations of an [`org.polyvariant.ndjson#ndjsonRestJson`](https://github.com/polyvariant/smithy4s-ndjson)
service may stream their request body, their response body, or both. Smithy restricts
`@streaming` to `blob` and `union`, and the protocol requires such a member to carry
`@httpPayload`, which gives exactly two framings — applied identically in both directions:

| Smithy | Wire | TypeScript |
| --- | --- | --- |
| `@streaming blob` | `application/octet-stream`, body verbatim | `AsyncIterable<Uint8Array>` |
| `@streaming union` | `application/x-ndjson`, one JSON value per line | `AsyncIterable<TheUnion>` |

A streamed member is surfaced as an `AsyncIterable` on the generated type itself, so a
streaming operation's signature reads like any other:

```ts
const client = new WatcherClient(transport, streamTransport)

// @streaming union out
const { events } = await client.watch({ id })
for await (const event of events) { /* typed as WatchEvent */ }

// @streaming blob in
await client.upload({ id, body: chunks /* AsyncIterable<Uint8Array> */ })
```

The promise resolves as soon as the response status is known; elements are pulled lazily,
and ndjson elements are validated against the union's schema one at a time (a bad element
throws `StreamDecodeError` at that element rather than truncating the stream). Because a
streamed response commits its status before the first element, **a mid-stream failure can't
be an HTTP status** — model it as a member of the streamed union (the protocol expects a
terminal member such as `completed` / `failed`).

Framing itself is the transport's job: `@polyvariant/smithy-ts-runtime` does it for you, and
a hand-rolled transport implements `StreamTransport.requestStream`, which
receives `requestStreamEncoding` / `responseStreamEncoding` telling it which framing to
apply, so it never has to guess from a content type. The generated code adds the per-element
schema on top. Services with streaming operations take both halves —
`new XxxClient(transport, streamTransport)`; services without one are unchanged, and a model
with no streaming emits no streaming code at all.

Mocks mirror the client, so a story can implement a streaming operation as an async generator:

```ts
mockService(WatcherMock, {
  watch: async (input) => ({
    events: (async function* () {
      yield { item: { name: 'one' } }
      yield { completed: {} }
    })(),
  }),
})
```

Implementations of both halves ship in
[`@polyvariant/smithy-ts-runtime`](runtime/) — see [Transports](#transports).

## Transports

The codegen emits the `Transport` / `StreamTransport` *interfaces*; the
implementation lives in [`@polyvariant/smithy-ts-runtime`](runtime/), published
separately so a generated file stays dependency-free.

```sh
pnpm add @polyvariant/smithy-ts-runtime
```

```ts
import { chain, fetchTransport, withHeaders } from '@polyvariant/smithy-ts-runtime'
import { DirectoryClient, FeedClient } from './generated.js'

const transport = chain(
  fetchTransport({ baseUrl: '/api' }),
  withHeaders(() => ({ authorization: `Bearer ${token()}` })),
)

const directory = new DirectoryClient(transport)
const feed = new FeedClient(transport, transport)   // streaming ops take both
```

It covers the whole contract — unary requests, ndjson and binary framing in both
directions, 401 handling, and a middleware seam (`chain` / `around` / `tap` /
`interceptorStack`) for tracing, auth headers and error reporting. The framing
primitives are exported on their own for transports it doesn't ship.

The library imports nothing from generated code: it declares structural copies of
the transport types, which TypeScript matches by shape. `typecheck/src/runtimeUsage.ts`
compiles the two against each other, so the pairing can't drift silently.

See [runtime/README.md](runtime/README.md) for the full API.

## Conventions & limits

- Only `alloy#simpleRestJson` services get clients; every operation needs an `@http` trait.
- Shapes in `smithy.api`, `smithy4s.*`, and `alloy.*` namespaces, mixins, and trait definitions
  are not emitted as data types.
- **Recursive shapes are not supported** — the generator topologically sorts shapes into one
  file and fails on cycles.
- Timestamps become `z.coerce.date()`; blobs become `z.string()` (a `@streaming` blob instead
  becomes `AsyncIterable<Uint8Array>`, with no zod schema — there is nothing to validate).
- A `@streaming` member is left out of its structure's zod schema (validating it would mean
  consuming the stream); the generated *type* still carries it, as an `AsyncIterable`.

## Development

```
sbt test                  # unit tests
sbt sbtPlugin/scripted    # the sbt plugin, end to end
sbt tsCodegenSample       # regenerate typecheck/src/generated.ts
nix flake check           # type-check + test the TypeScript side
pnpm check                # the same, without nix (needs `pnpm install` first)
```

The TypeScript lives in a pnpm workspace of two packages:

- `runtime/` — the published transport library. `pnpm --filter @polyvariant/smithy-ts-runtime
  run check` builds it, type-checks it and runs its `node:test` suite (framing round-trips,
  the transport against a `fetch` double, middleware ordering).
- `typecheck/` — a model (`model.smithy`) exercising every construct the codegen emits, its
  committed output (`src/generated.ts`), a consumer-side `src/usage.ts` that uses the clients,
  streams and mocks the way a caller would, and `src/runtimeUsage.ts`, which drives those same
  clients with the *library's* transport. That last file is what pins the library's structural
  transport types to the ones the codegen emits — change one without the other and it stops
  compiling.

`nix flake check` runs both under `strict` + `erasableSyntaxOnly`.

This matters because the Scala tests assert on substrings of the emitted file, which cannot
catch a type error — a generator declared as `AsyncIterable`, an intersection with an empty
`z.object`, a `Date` cast to a query value. After changing the generator, run
`sbt tsCodegenSample` and commit the result; CI fails if it drifts.

Changing anything under `runtime/` or `typecheck/` that moves the lockfile means updating
`pnpmDeps.hash` in `nix/typecheck.nix` — build once, and nix prints the hash it wanted.
Note that `nix build` only sees git-tracked files, so `git add` new files before running it.

A `nix develop` shell provides node, pnpm, sbt and a JDK.

### Releasing

A `v*` tag ships both halves at the same version: sbt-typelevel publishes the JVM artifacts
from the generated `ci.yml`, and `.github/workflows/npm-publish.yml` publishes
`@polyvariant/smithy-ts-runtime` to npm. The tag is the only source of version truth —
`runtime/package.json` keeps a placeholder `0.0.0` that the workflow overwrites, so there is no
version to bump by hand.

`ci.yml` is generated (`sbt githubWorkflowGenerate`) and CI fails if it drifts; the npm
workflow is hand-written for that reason. Publishing needs an `NPM_TOKEN` secret with publish
rights on the `@polyvariant` scope.

## Dependencies

`smithy-build`, `smithy-codegen-core`, `smithy-model`, `alloy-core`, `smithy4s-protocol`, and
`smithy4s-ndjson-protocol` (the trait definition only — nothing Scala-specific from
smithy4s-ndjson is needed, since the codegen keys off `@streaming` members).

## License

Apache 2.0.
