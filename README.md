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
      "excludeServices": ["myorg.auth#AuthService"],
      "pathPrefix": "/internal/v1"
    }
  }
}
```

Settings:

- `outFile` — destination filename within the plugin's output dir (default `generated.ts`).
- `excludeServices` — fully-qualified service shape ids (`namespace#Name`) to skip. Their
  referenced data shapes are still emitted; only the operations and the client class are
  dropped. Use this for services you hand-roll (streaming, custom routing, …).
- `pathPrefix` — path prepended to every operation's `@http` URI (default: none). For a service
  mounted under a prefix that the model itself does not describe — a server framework that
  derives one from a trait, or a reverse proxy. A leading slash is optional and a trailing one
  is ignored, so `"internal/v1"`, `"/internal/v1"` and `"/internal/v1/"` are equivalent. The
  prefix applies to the generated Storybook mocks too, so mocked routes keep matching the
  client.

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
tsCodegenPathPrefix := "/internal/v1"
```

then run `tsCodegen`. The plugin resolves the `smithy-ts-codegen-cli` artifact (at the plugin's
own version) with coursier and runs it in a forked JVM, so nothing about your project's Scala
version affects the codegen. Settings:

- `tsCodegenSmithyDirs` — dirs scanned for `*.smithy` / `*.json` (default `src/main/smithy`).
- `tsCodegenOutputFile` — where to write the TypeScript (default `target/generated.ts`).
- `tsCodegenExcludeServices` — service shape ids to skip (see above).
- `tsCodegenPathPrefix` — path prepended to every `@http` URI (see above; default none).
- `tsCodegenVersion` — override the codegen version to resolve (defaults to the plugin's own).

### Programmatically (`TsCodegenPlugin.generate`)

The core is a pure function from a loaded `software.amazon.smithy.model.Model` to a `String`:

```scala
import org.polyvariant.smithy.ts.TsCodegenPlugin
import software.amazon.smithy.model.Model

val model: Model = ???
val ts: String = TsCodegenPlugin.generate(model, excludeServices = Set.empty, pathPrefix = "")
```

### From any build (forked JVM)

The `smithy-ts-codegen-cli` artifact's `org.polyvariant.smithy.ts.cli.Main` assembles a model
from `.smithy`/`.json` sources and writes the output file. Run it with the CLI (and its deps) on
the classpath:

```
Main <smithyDirs (path-separator-joined)> <outFile> [<excludeServices (comma-joined)> [<pathPrefix>]]
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
// every operation here streams, so only the streaming half is needed
const client = new WatcherClient(streamTransport)

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
schema on top. A client takes exactly the halves its operations use: a service that mixes
streaming and unary operations takes both — `new XxxClient(transport, streamTransport)` — one
whose operations all stream takes `new XxxClient(streamTransport)`, and one that streams
nothing is unchanged at `new XxxClient(transport)`. A model with no streaming anywhere emits
no streaming code at all.

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

## Open types

Both of alloy's open-type traits are honored, so a model can evolve without breaking clients
compiled against an older copy of it.

`@openEnum` widens the schema to accept any string. The type is written out rather than inferred
from it, so the known values survive as editor completions:

```ts
export const CategorySchema = z.union([z.enum(['book', 'film']), z.string()])
export type Category = "book" | "film" | (string & {})
```

A union member tagged `@jsonUnknown` makes the union open. On the wire, any discriminator key the
model does not know activates that member, carrying the whole `{ <unknownKey>: <payload> }`
object — so it is not a variant of its own, but a catch-all arm, emitted last (`z.union` tries its
arms in order, and a permissive record placed earlier would swallow every known variant):

```ts
export const FigureSchema = z.union([
  z.object({ circle: CircleSchema }),
  z.object({ square: SquareSchema }),
  z.record(z.string(), z.unknown()),
])
```

Branch over the known keys with `in`, and treat anything left as the unknown case.

### Discriminated unions

`@discriminated` changes the encoding: instead of a single-key envelope, the variant is flattened
into the object and labelled with a discriminator property. A closed one becomes a
`z.discriminatedUnion`, which dispatches on that property in one step and reports errors against the
selected arm rather than against every arm:

```ts
export const RegionSchema = z.discriminatedUnion('kind', [
  CircleSchema.extend({ 'kind': z.literal('circle') }),
  SquareSchema.extend({ 'kind': z.literal('square') }),
])
```

Adding `@jsonUnknown` makes it open, and the schema falls back to a plain `z.union`.
`z.discriminatedUnion` builds its dispatch map from the arms' literal discriminator values, and
throws when constructed with an arm whose discriminator is a plain `z.string()` — so it cannot
express the catch-all. As in the tagged case the catch-all comes last, since `z.union` dispatches by
trial and it would otherwise match every known variant:

```ts
export const ZoneSchema = z.union([
  CircleSchema.extend({ 'kind': z.literal('circle') }),
  SquareSchema.extend({ 'kind': z.literal('square') }),
  z.object({ 'kind': z.string() }).catchall(z.unknown()),
])
```

One consequence is worth knowing: a *known* discriminator carrying a payload that does not validate
lands in the catch-all rather than failing. That follows from what an open union asks for — a client
built against an older model cannot tell a malformed variant from a newer one it does not know — but
it does mean an open discriminated union validates its known variants less strictly than a closed
one. Keep the union closed where you want the stricter errors.

Members are flattened into the encoded object, so they have to target structures; a member pointing
at a string or a list fails codegen with an error naming it.

## Large numbers: `@lossless`

JavaScript numbers are IEEE-754 doubles, so an integer outside ±(2^53 - 1) cannot be represented
exactly. `JSON.parse` rounds such a value on the way in, before any schema can see it, and the
original is unrecoverable — a `long` or `bigInteger` whose values reach that far therefore has no
lossless `number` form on the client.

`@lossless`, from the `smithy-ts-codegen-traits` artifact, marks a member whose exact value must
survive:

```
$version: "2"

namespace example

use org.polyvariant.smithy.ts#lossless

structure Measurement {
    sequence: Integer
    seed: Long
}

apply Measurement$seed @lossless
```

```ts
export const MeasurementSchema = z.object({
  sequence: z.number().int().optional(),
  seed: z.union([z.number(), z.string()]).optional(),
})
```

The type is `number | string` because the representation is decided per value at runtime: anything
that fits exactly arrives as a `number`, so ordinary values stay ordinary, and only a value that
would lose precision surfaces as its exact decimal string. On the way out the generated client
converts the member to a `bigint`, which the serializer writes as a bare numeric literal — so
`number`, `string` and `bigint` are all accepted, and all reach the wire unquoted.

**This requires a lossless transport.** `@polyvariant/smithy-ts-runtime` is one. A transport built
on plain `JSON.parse` / `JSON.stringify` cannot honor the trait: `JSON.parse` will have rounded the
value before the schema runs, and `JSON.stringify` throws on a `bigint`. A hand-rolled transport
should use the exported `parseLossless` / `stringifyLossless` in place of the built-ins. Nothing in
the generated code can detect the difference, so this is on you to wire up.

The trait is member-scoped, not shape-scoped: whether a field can exceed the safe range is a
property of that field, and the same numeric shape is usually reused for values that stay well
inside it. Applying it to `Measurement$seed` above leaves every other `Long` in the model a
`number`. It applies in HTTP bindings too — a `@lossless` member bound to a label, query parameter
or header is passed through as-is rather than coerced with `Number(...)`, since those are strings on
the wire and were never lossy.

It is restricted to integral shapes (`byte` through `long`, and `bigInteger`). The exact value
travels as a `bigint`, which has no fractional form, so `float`, `double` and `bigDecimal` would
need a different carrier and the selector rejects them rather than promising something it cannot
deliver.

Depend on the traits artifact to `apply` it:

```scala
libraryDependencies += "org.polyvariant" % "smithy-ts-codegen-traits" % "<version>"
```

It is a plain smithy model under `META-INF/smithy`, kept out of `smithy-ts-codegen` so a model
can depend on the trait definitions without pulling the generator and its dependencies onto the
model's classpath.

Note this changes only the *TypeScript* representation — the wire format is still a JSON number,
so a client sending such a member is responsible for serializing it back as an unquoted numeric
literal.

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

`smithy-build`, `smithy-codegen-core`, `smithy-model`, `alloy-core`, `smithy4s-protocol`,
`smithy4s-ndjson-protocol` (the trait definition only — nothing Scala-specific from
smithy4s-ndjson is needed, since the codegen keys off `@streaming` members), and
`smithy-ts-codegen-traits` (this project's own codegen-controlling traits).

## License

Apache 2.0.
