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
3. **Transport** — a small `Transport` interface plus request/response types you implement once
   (over `fetch`, axios, …). The generated clients are transport-agnostic.
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

## Conventions & limits

- Only `alloy#simpleRestJson` services get clients; every operation needs an `@http` trait.
- Shapes in `smithy.api`, `smithy4s.*`, and `alloy.*` namespaces, mixins, and trait definitions
  are not emitted as data types.
- **Recursive shapes are not supported** — the generator topologically sorts shapes into one
  file and fails on cycles.
- Timestamps become `z.coerce.date()`; blobs become `z.string()`.

## Dependencies

`smithy-build`, `smithy-codegen-core`, `smithy-model`, `alloy-core`, and `smithy4s-protocol`.

## License

Apache 2.0.
