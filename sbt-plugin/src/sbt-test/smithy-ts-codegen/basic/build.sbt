enablePlugins(SmithyTsCodegenPlugin)

scalaVersion := "2.12.20"

// Resolve the codegen artifact (published locally by the scripted run) and the
// snapshot dependencies it pulls in.
resolvers += Resolver.sonatypeCentralSnapshots

tsCodegenSmithyDirs := Seq(baseDirectory.value / "src" / "main" / "smithy")
tsCodegenOutputFile := baseDirectory.value / "target" / "generated.ts"
tsCodegenExcludeServices := Seq("test#HiddenService")

TaskKey[Unit]("checkOutput") := {
  val f = tsCodegenOutputFile.value
  assert(f.exists, s"expected $f to exist")
  val contents = IO.read(f)
  def require(sub: String): Unit =
    assert(contents.contains(sub), s"expected generated.ts to contain: $sub")

  require("export const PersonSchema = z.object({")
  require("export type Person = z.infer<typeof PersonSchema>")
  require("export class GreeterClient {")
  // excluded service must not get a client...
  assert(!contents.contains("export class HiddenServiceClient {"), "HiddenService client should be excluded")
  // ...but its referenced data shape is still emitted
  require("export const SecretSchema = z.object({")

  // Streaming: the transport half, and both framings.
  require("export interface StreamTransport {")
  require("export class FeedClient {")
  // both of Feed's operations stream, so `transport.request` is never called —
  // the client takes the streaming half alone
  require("constructor(streamTransport: StreamTransport) {")
  // ...while Greeter, which streams nothing, keeps the unary half
  require("constructor(transport: Transport) {")
  // an ndjson output stream, element-checked against the union schema
  require("responseStreamEncoding: 'ndjson',")
  require("decodeStream(res.stream, FeedEventSchema,")
  // a streamed member alongside a bound one: the stream is intersected in
  require("export type WatchOutput = z.infer<typeof WatchOutputSchema> & { events: AsyncIterable<FeedEvent> }")
  // ...whereas Upload's input streams its only body member
  require("export type Bytes = AsyncIterable<Uint8Array>")
  // a binary input stream: a stream type, never a value schema
  require("requestStreamEncoding: 'binary',")
  require("export type Bytes = AsyncIterable<Uint8Array>")
  assert(!contents.contains("BytesSchema"), "a streaming blob must not get a zod schema")
}
