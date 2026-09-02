/*
 * Copyright 2026 Polyvariant
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.polyvariant.smithy.ts

import org.polyvariant.smithy.ts.api.PathSegment
import org.polyvariant.smithy.ts.api.TsCodegenExtension
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ServiceShape

import scala.jdk.CollectionConverters.*

class TsCodegenPluginTest extends munit.FunSuite {

  private def model(smithy: String): Model =
    Model
      .assembler()
      .discoverModels(this.getClass.getClassLoader)
      .addUnparsedModel("test.smithy", smithy)
      .assemble()
      .unwrap()

  private def generate(
    smithy: String,
    exclude: Set[String] = Set.empty,
    extensions: List[TsCodegenExtension] = Nil,
  ): String =
    TsCodegenPlugin.generate(model(smithy), exclude, extensions)

  /** Several model files at once — the only way to put two namespaces in one model, since a
    * `.smithy` file declares exactly one.
    */
  private def generateAll(sources: String*): String = {
    val assembler = Model.assembler().discoverModels(this.getClass.getClassLoader)
    sources.zipWithIndex.foreach { case (src, i) =>
      val _ = assembler.addUnparsedModel(s"test-$i.smithy", src)
    }
    TsCodegenPlugin.generate(assembler.assemble().unwrap(), Set.empty, Nil)
  }

  /** A service streaming a union out, over the ndjson protocol. */
  private val ndjsonModel =
    """|$version: "2"
       |namespace test
       |
       |use org.polyvariant.ndjson#ndjsonRestJson
       |
       |@ndjsonRestJson
       |service Watcher {
       |  operations: [Watch]
       |}
       |
       |@http(method: "GET", uri: "/watch/{id}")
       |operation Watch {
       |  input := {
       |    @required
       |    @httpLabel
       |    id: String
       |  }
       |  output := {
       |    @required
       |    @httpPayload
       |    events: WatchEvent
       |  }
       |}
       |
       |@streaming
       |union WatchEvent {
       |  item: Item
       |  completed: Completed
       |}
       |
       |structure Item {
       |  @required
       |  name: String
       |}
       |
       |structure Completed {}
       |""".stripMargin

  test("emits a zod schema + type for a simple structure") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |structure Person {
         |  @required
         |  name: String
         |  age: Integer
         |}
         |""".stripMargin
    )

    assert(clue(out).contains("export const PersonSchema = z.object({"))
    assert(out.contains("name: z.string(),"))
    assert(out.contains("age: z.number().int().optional(),"))
    assert(out.contains("export type Person = z.infer<typeof PersonSchema>"))
  }

  test("emits an enum schema") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |enum Color {
         |  RED = "red"
         |  GREEN = "green"
         |}
         |""".stripMargin
    )

    assert(clue(out).contains("export const ColorSchema = z.enum(['red', 'green'])"))
  }

  test("emits a list schema") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |list Names {
         |  member: String
         |}
         |""".stripMargin
    )

    assert(clue(out).contains("export const NamesSchema = z.array(z.string())"))
  }

  test("brands a simple string alias") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |string UserId
         |""".stripMargin
    )

    assert(clue(out).contains("export const UserIdSchema = z.string().brand<'UserId'>()"))
  }

  test("emits an Error class for @error shapes") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |@error("client")
         |@httpError(404)
         |structure NotFound {
         |  @required
         |  message: String
         |}
         |""".stripMargin
    )

    assert(clue(out).contains("export class NotFoundError extends Error {"))
    assert(out.contains("static readonly status = 404"))
    assert(out.contains("static readonly errorType = 'NotFound'"))
  }

  test("emits a typed client + mock for a simpleRestJson service") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |use alloy#simpleRestJson
         |
         |@simpleRestJson
         |service Greeter {
         |  operations: [Greet]
         |}
         |
         |@http(method: "POST", uri: "/greet/{name}")
         |operation Greet {
         |  input := {
         |    @required
         |    @httpLabel
         |    name: String
         |    greeting: String
         |  }
         |  output := {
         |    @required
         |    message: String
         |  }
         |}
         |""".stripMargin
    )

    assert(clue(out).contains("export class GreeterClient {"))
    assert(out.contains("async greet(input: GreetInput"))
    assert(out.contains("method: 'POST',"))
    assert(out.contains("`/greet/${encodeURIComponent(String(input.name))}`"))
    // http-bound label member is omitted from the JSON body
    assert(out.contains("body: { greeting: input.greeting },"))
    // mock stubs
    assert(out.contains("export interface GreeterHandlers {"))
    assert(out.contains("export const GreeterMock: MockServiceDescriptor<GreeterHandlers> = {"))
  }

  test("streams an ndjson union output as an AsyncIterable") {
    val out = generate(ndjsonModel)

    // the streamed member becomes an AsyncIterable of the union type, carried by
    // the generated output type itself rather than bolted on at the call site
    assert(clue(out).contains("watch(input: WatchInput"))
    // `events` is the only member here, so the type is the stream alone —
    // intersecting with `z.infer` of an empty object would make it `never`.
    assert(out.contains("export type WatchOutput = { events: AsyncIterable<WatchEvent> }"))
    // ...and it is left out of the zod object, which has nothing to validate it with
    assert(out.contains("export const WatchOutputSchema = z.object({\n})"))
    assert(
      out.contains("async watch(input: WatchInput, opts?: TransportOptions): Promise<WatchOutput>")
    )
    // the transport is told how to frame the response
    assert(out.contains("responseStreamEncoding: 'ndjson',"))
    assert(out.contains("const res = await this.streamTransport.requestStream({"))
    // each element is validated against the union schema as it is pulled
    assert(out.contains("decodeStream(res.stream, WatchEventSchema,"))
    // every operation here streams, so the unary half would never be called —
    // the client takes the streaming half alone
    assert(out.contains("constructor(streamTransport: StreamTransport) {"))
    assert(!out.contains("private readonly transport: Transport"))
  }

  test("streams a binary blob input as an AsyncIterable of chunks") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |use org.polyvariant.ndjson#ndjsonRestJson
         |
         |@ndjsonRestJson
         |service Uploader {
         |  operations: [Upload]
         |}
         |
         |@http(method: "POST", uri: "/upload/{id}")
         |operation Upload {
         |  input := {
         |    @required
         |    @httpLabel
         |    id: String
         |    @required
         |    @httpPayload
         |    body: Chunks
         |  }
         |}
         |
         |@streaming
         |blob Chunks
         |""".stripMargin
    )

    assert(
      clue(out).contains(
        "export type UploadInput = z.infer<typeof UploadInputSchema> & { body: AsyncIterable<Uint8Array> }"
      )
    )
    // a streaming blob is a stream type, never a value with a schema
    assert(out.contains("export type Chunks = AsyncIterable<Uint8Array>"))
    assert(!out.contains("ChunksSchema"))
    assert(out.contains("requestStreamEncoding: 'binary',"))
    assert(out.contains("stream: input.body,"))
    // the stream replaces the JSON body
    assert(out.contains("body: undefined,"))
  }

  test("a structure whose only member streams is the stream type alone") {
    // `z.infer` of an empty `z.object({})` is `Record<string, never>` under zod
    // v4; intersecting with it makes every property `never`, so the generated
    // type has to skip the intersection entirely when nothing else remains.
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |use org.polyvariant.ndjson#ndjsonRestJson
         |
         |@ndjsonRestJson
         |service Echoer {
         |  operations: [Echo]
         |}
         |
         |@http(method: "POST", uri: "/echo")
         |operation Echo {
         |  input := {
         |    @required
         |    @httpPayload
         |    incoming: Event
         |  }
         |}
         |
         |@streaming
         |union Event {
         |  item: Item
         |}
         |
         |structure Item {
         |  @required
         |  name: String
         |}
         |""".stripMargin
    )

    assert(clue(out).contains("export type EchoInput = { incoming: AsyncIterable<Event> }"))
    assert(!out.contains("export type EchoInput = z.infer"))
  }

  test("serialises a @httpQuery timestamp rather than casting it") {
    // A `Date` doesn't fit `string | number | boolean`, so a blanket cast is a
    // type error at the call site.
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |use alloy#simpleRestJson
         |
         |@simpleRestJson
         |service Searcher {
         |  operations: [Search]
         |}
         |
         |@http(method: "GET", uri: "/search")
         |operation Search {
         |  input := {
         |    @httpQuery("since")
         |    since: Timestamp
         |    @httpQuery("limit")
         |    limit: Integer
         |  }
         |}
         |""".stripMargin
    )

    assert(clue(out).contains("query['since'] = input.since.toISOString()"))
    assert(out.contains("query['limit'] = input.limit"))
    assert(!out.contains("as string | number | boolean"))
  }

  test("emits streaming-aware mock handlers") {
    val out = generate(ndjsonModel)

    // the handler mirrors the client: it returns the streamed member as an
    // AsyncIterable, so a story can implement it as an async generator
    assert(clue(out).contains("watch(input: WatchInput): WatchOutput | Promise<WatchOutput>"))
    assert(out.contains("responseStreamEncoding: 'ndjson',"))
    assert(out.contains("encodeStream: (output) =>"))
    // a streamed output has no JSON body
    assert(out.contains("encodeBody: () => undefined,"))
  }

  test("does not emit the streaming transport for a model without streaming") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |use alloy#simpleRestJson
         |
         |@simpleRestJson
         |service Greeter {
         |  operations: [Greet]
         |}
         |
         |@http(method: "GET", uri: "/greet")
         |operation Greet {
         |  output := {
         |    @required
         |    message: String
         |  }
         |}
         |""".stripMargin
    )

    assert(!clue(out).contains("StreamTransport"))
    assert(!out.contains("decodeStream"))
    // and the client keeps its single-argument constructor
    assert(out.contains("constructor(transport: Transport) {"))
  }

  test("a service mixing streaming and unary operations takes both transport halves") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |use org.polyvariant.ndjson#ndjsonRestJson
         |
         |@ndjsonRestJson
         |service Mixed {
         |  operations: [Watch, Ping]
         |}
         |
         |@http(method: "GET", uri: "/watch")
         |operation Watch {
         |  output := {
         |    @required
         |    @httpPayload
         |    events: WatchEvent
         |  }
         |}
         |
         |@http(method: "GET", uri: "/ping")
         |operation Ping {
         |  output := {
         |    @required
         |    message: String
         |  }
         |}
         |
         |@streaming
         |union WatchEvent {
         |  item: Item
         |}
         |
         |structure Item {
         |  @required
         |  name: String
         |}
         |""".stripMargin
    )

    // `ping` calls `transport.request`, `watch` calls `streamTransport.requestStream`,
    // so both halves are genuinely used and both are required.
    assert(
      clue(out).contains("constructor(transport: Transport, streamTransport: StreamTransport) {")
    )
    assert(out.contains("private readonly transport: Transport"))
    assert(out.contains("private readonly streamTransport: StreamTransport"))
  }

  test("a service with no operations still takes the unary transport") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |use alloy#simpleRestJson
         |
         |@simpleRestJson
         |service Empty {
         |  operations: []
         |}
         |""".stripMargin
    )

    // nothing is called either way; the unary half is the honest default, and it
    // keeps the signature stable once a first operation shows up
    assert(clue(out).contains("constructor(transport: Transport) {"))
  }

  test("rejects a @streaming member that is neither blob nor union") {
    // `@streaming` is restricted to blob/union by smithy's own selector, so the
    // model itself is what rejects this — the codegen never sees it.
    val ex = intercept[RuntimeException] {
      generate(
        """|$version: "2"
           |namespace test
           |
           |structure Holder {
           |  @required
           |  items: Items
           |}
           |
           |@streaming
           |list Items {
           |  member: String
           |}
           |""".stripMargin
      )
    }
    assert(clue(ex.getMessage).nonEmpty)
  }

  test("excludeServices skips the client but keeps the data shapes") {
    val smithy =
      """|$version: "2"
         |namespace test
         |
         |use alloy#simpleRestJson
         |
         |@simpleRestJson
         |service Greeter {
         |  operations: [Greet]
         |}
         |
         |@http(method: "GET", uri: "/greet")
         |operation Greet {
         |  output := {
         |    @required
         |    message: String
         |  }
         |}
         |""".stripMargin

    val out = generate(smithy, exclude = Set("test#Greeter"))

    assert(!clue(out).contains("export class GreeterClient {"))
    // referenced data shape still emitted
    assert(out.contains("export const GreetOutputSchema = z.object({"))
  }

  test("fails on recursive shapes") {
    val ex = intercept[RuntimeException] {
      generate(
        """|$version: "2"
           |namespace test
           |
           |structure A {
           |  b: B
           |}
           |
           |structure B {
           |  a: A
           |}
           |""".stripMargin
      )
    }
    assert(clue(ex.getMessage).contains("does not support recursive shapes"))
  }

  // The model stores shapes in a hash map keyed by ShapeId, so iteration order
  // varies between environments. Independent shapes must therefore come out in
  // shape-id order, or the emitted file differs machine-to-machine.
  test("emits mutually independent shapes in shape id order") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |enum Zebra {
         |  A = "a"
         |}
         |
         |enum Apple {
         |  B = "b"
         |}
         |
         |enum Mango {
         |  C = "c"
         |}
         |""".stripMargin
    )

    val emitted = List("Apple", "Mango", "Zebra").map(n => n -> out.indexOf(s"const ${n}Schema"))
    assert(emitted.forall(_._2 >= 0), clue(emitted))
    assertEquals(emitted.sortBy(_._2).map(_._1), List("Apple", "Mango", "Zebra"))
  }

  // Ordering by shape id must not override the topological constraint: a shape
  // has to follow everything it references, whatever the names sort like.
  test("orders dependencies before dependents even against shape id order") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |structure Aaa {
         |  z: Zzz
         |}
         |
         |structure Zzz {
         |  name: String
         |}
         |""".stripMargin
    )

    assert(out.indexOf("const ZzzSchema") < out.indexOf("const AaaSchema"), clue(out))
  }

  // The regression that motivated the sort: `model.shapes` handed the generator a
  // different order on macOS than on the CI runner, so the committed output drifted.
  // Feeding topoSort shuffled input the way `generate` does must yield one ordering.
  test("topoSort output is stable regardless of input order") {
    val base = model(
      """|$version: "2"
         |namespace test
         |
         |structure Order { item: Item, cust: Customer }
         |structure Customer { name: Username, tags: TagList }
         |structure Item { sku: Sku, price: Integer }
         |list TagList { member: String }
         |string Username
         |string Sku
         |
         |enum Status {
         |  A = "a"
         |  B = "b"
         |}
         |
         |enum Direction {
         |  ASC = "asc"
         |  DESC = "desc"
         |}
         |""".stripMargin
    )

    val shapes = base
      .shapes()
      .iterator()
      .asScala
      .toList
      .filter(s => s.getId.getNamespace == "test" && !s.isMemberShape)

    val orders =
      (1 to 200).map { seed =>
        val shuffled = scala.util.Random(seed).shuffle(shapes)
        TsCodegenPlugin.topoSort(shuffled.sortBy(_.getId.toString)).map(_.getId.toString)
      }.distinct

    assertEquals(orders.size, 1, s"got ${orders.size} distinct orderings across shuffles")
  }

  test("an @openEnum accepts unknown values, keeping the known ones as completions") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |use alloy#openEnum
         |
         |@openEnum
         |enum Category {
         |  BOOK = "book"
         |  FILM = "film"
         |}
         |""".stripMargin
    )

    assert(
      out.contains("export const CategorySchema = z.union([z.enum(['book', 'film']), z.string()])"),
      out,
    )
    // Not `z.infer`: that collapses the union to plain `string` and loses the literals.
    assert(out.contains("""export type Category = "book" | "film" | (string & {})"""), out)
  }

  test("a closed enum is unchanged") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |enum Category {
         |  BOOK = "book"
         |  FILM = "film"
         |}
         |""".stripMargin
    )

    assert(out.contains("export const CategorySchema = z.enum(['book', 'film'])"), out)
    assert(out.contains("export type Category = z.infer<typeof CategorySchema>"), out)
  }

  test("a @jsonUnknown member makes the union open, with the catch-all arm last") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |use alloy#jsonUnknown
         |
         |union Figure {
         |  circle: Circle
         |  @jsonUnknown
         |  other: Document
         |}
         |
         |structure Circle {
         |  @required
         |  radius: Integer
         |}
         |""".stripMargin
    )

    // The tagged member is not a variant of its own — no `{ other: ... }` arm.
    assert(!out.contains("z.object({ other:"), out)
    val schema =
      out
        .linesIterator
        .dropWhile(!_.contains("FigureSchema = "))
        .takeWhile(!_.contains("])"))
        .toList
    assert(schema.exists(_.contains("z.object({ circle: CircleSchema })")), out)
    // Ordering matters: z.union tries arms in order, so a permissive record
    // placed earlier would swallow every known variant.
    val knownIdx = schema.indexWhere(_.contains("circle:"))
    val unknownIdx = schema.indexWhere(_.contains("z.record(z.string(), z.unknown())"))
    assert(knownIdx >= 0 && unknownIdx > knownIdx, schema.mkString("\n"))
  }

  test("a closed union has no catch-all arm") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |union Figure {
         |  circle: Circle
         |}
         |
         |structure Circle {
         |  @required
         |  radius: Integer
         |}
         |""".stripMargin
    )

    assert(!out.contains("z.record(z.string(), z.unknown())"), out)
  }

  test("@lossless widens a numeric member to number | string, per member and not per shape") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |use org.polyvariant.smithy.ts#lossless
         |
         |structure Measurement {
         |  sequence: Integer
         |  seed: Long
         |}
         |
         |structure Counter {
         |  @required
         |  total: Long
         |}
         |
         |apply Measurement$seed @lossless
         |""".stripMargin
    )

    // A lossless transport hands back a number when the value fits and its exact
    // decimal string when it does not, so the schema has to admit both.
    assert(out.contains("seed: z.union([z.number(), z.string()]).optional()"), out)
    // Same `Long`, no trait — still a plain number.
    assert(out.contains("total: z.number().int()"), out)
    assert(out.contains("sequence: z.number().int().optional()"), out)
  }

  test("a @lossless member bound to a header or query is not coerced with Number()") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |use alloy#simpleRestJson
         |use org.polyvariant.smithy.ts#lossless
         |
         |@simpleRestJson
         |service Directory {
         |  operations: [Measure]
         |}
         |
         |@http(method: "GET", uri: "/measurements")
         |operation Measure {
         |  input := {
         |    @httpQuery("offset")
         |    offset: Long
         |  }
         |  output := {
         |    @httpHeader("x-total")
         |    total: Long
         |  }
         |}
         |
         |apply MeasureInput$offset @lossless
         |apply MeasureOutput$total @lossless
         |""".stripMargin
    )

    // `Number(...)` is the rounding the trait exists to avoid, and the schema
    // accepts the raw string anyway.
    assert(out.contains("raw['total'] = res.headers['x-total']"), out)
    assert(!out.contains("Number(res.headers['x-total'])"), out)
  }

  test("a @lossless body member is sent as a bigint, so it serialises unquoted") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |use alloy#simpleRestJson
         |use org.polyvariant.smithy.ts#lossless
         |
         |@simpleRestJson
         |service Seeder {
         |  operations: [Sequence]
         |}
         |
         |@http(method: "POST", uri: "/sequence")
         |operation Sequence {
         |  input := {
         |    @required
         |    seed: Long
         |
         |    @required
         |    count: Integer
         |  }
         |}
         |
         |apply SequenceInput$seed @lossless
         |""".stripMargin
    )

    // A numeric string would be written back as a *quoted* string, changing the
    // type the server sees; a bigint serialises as a bare literal at full range.
    // The member is required, so it needs no undefined guard.
    assert(out.contains("seed: BigInt(input.seed)"), out)
    // The untagged member is untouched.
    assert(out.contains("count: input.count"), out)
  }

  test("an optional @lossless body member stays absent rather than becoming BigInt(undefined)") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |use alloy#simpleRestJson
         |use org.polyvariant.smithy.ts#lossless
         |
         |@simpleRestJson
         |service Seeder {
         |  operations: [Sequence]
         |}
         |
         |@http(method: "POST", uri: "/sequence")
         |operation Sequence {
         |  input := {
         |    seed: Long
         |  }
         |}
         |
         |apply SequenceInput$seed @lossless
         |""".stripMargin
    )

    // `BigInt(undefined)` throws, so the guard is what keeps an absent optional
    // member absent instead of failing the request.
    assert(out.contains("input.seed === undefined ? undefined : BigInt(input.seed)"), out)
  }

  test("a @discriminated union dispatches on its discriminator property") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |use alloy#discriminated
         |
         |@discriminated("kind")
         |union Region {
         |  circle: Circle
         |  square: Square
         |}
         |
         |structure Circle {
         |  @required
         |  radius: Integer
         |}
         |
         |structure Square {
         |  @required
         |  side: Integer
         |}
         |""".stripMargin
    )

    assert(out.contains("export const RegionSchema = z.discriminatedUnion('kind', ["), out)
    // The variant is flattened into the encoded object, so the arm is the target
    // structure itself, labelled with the discriminator — not a single-key envelope.
    assert(out.contains("CircleSchema.extend({ 'kind': z.literal('circle') }),"), out)
    assert(out.contains("SquareSchema.extend({ 'kind': z.literal('square') }),"), out)
    assert(!out.contains("z.object({ circle:"), out)
  }

  test("an open @discriminated union falls back to z.union, with the catch-all arm last") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |use alloy#discriminated
         |use alloy#jsonUnknown
         |
         |@discriminated("kind")
         |union Zone {
         |  circle: Circle
         |  @jsonUnknown
         |  other: Document
         |}
         |
         |structure Circle {
         |  @required
         |  radius: Integer
         |}
         |""".stripMargin
    )

    // z.discriminatedUnion builds its dispatch map from the arms' literal
    // discriminators, and throws when constructed with a `z.string()` arm — so an
    // open discriminated union has to be a plain z.union.
    assert(out.contains("export const ZoneSchema = z.union(["), out)
    assert(!out.contains("ZoneSchema = z.discriminatedUnion"), out)

    val schema =
      out
        .linesIterator
        .dropWhile(!_.contains("ZoneSchema = "))
        .takeWhile(!_.contains("])"))
        .toList
    val knownIdx = schema.indexWhere(_.contains("CircleSchema.extend("))
    val unknownIdx = schema.indexWhere(_.contains(".catchall(z.unknown())"))
    // Trial dispatch: the catch-all matches any object carrying the discriminator,
    // so ahead of the known arms it would swallow all of them.
    assert(knownIdx >= 0 && unknownIdx > knownIdx, schema.mkString("\n"))
    assert(schema.exists(_.contains("z.object({ 'kind': z.string() }).catchall(z.unknown())")), out)
  }

  test("rejects a @discriminated union whose member does not target a structure") {
    val e = intercept[Exception](
      generate(
        """|$version: "2"
           |namespace test
           |
           |use alloy#discriminated
           |
           |@discriminated("kind")
           |union Region {
           |  circle: Circle
           |  label: String
           |}
           |
           |structure Circle {
           |  @required
           |  radius: Integer
           |}
           |""".stripMargin
      )
    )

    assert(e.getMessage.contains("test#Region"), e.getMessage)
    assert(e.getMessage.contains("label"), e.getMessage)
  }

  // --------------------------------------------------------------------------
  // smithy.api#Unit as a member target
  // --------------------------------------------------------------------------

  test("a union member targeting Unit is an empty object, not a failure") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |union Result {
         |  found: Item
         |  notFound: Unit
         |}
         |
         |structure Item {
         |  @required
         |  name: String
         |}
         |""".stripMargin
    )

    assert(clue(out).contains("export const UnitSchema = z.object({"))
    assert(out.contains("z.object({ notFound: UnitSchema })"))
  }

  test("Unit is still 'no body' as an operation input and output") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |use alloy#simpleRestJson
         |
         |@simpleRestJson
         |service Pinger {
         |  operations: [Ping]
         |}
         |
         |@http(method: "POST", uri: "/ping")
         |operation Ping {}
         |""".stripMargin
    )

    // No input parameter, and `void` rather than `Unit` as the result type.
    assert(clue(out).contains("async ping(opts?: TransportOptions): Promise<void>"))
    assert(!out.contains("input: Unit"))
  }

  // --------------------------------------------------------------------------
  // Trait definitions are never emitted
  // --------------------------------------------------------------------------

  test("a trait definition is not emitted, but a plain shape beside it is") {
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |@trait(selector: "member")
         |structure sensitiveHint {
         |  reason: String
         |}
         |
         |structure Payload {
         |  @required
         |  value: String
         |}
         |""".stripMargin
    )

    assert(!clue(out).contains("sensitiveHint"))
    assert(out.contains("export const PayloadSchema = z.object({"))
  }

  test("a non-prelude namespace's data shapes are emitted even when it also defines traits") {
    // `alloy#UUID` is a plain `string` shape living in a namespace that mostly holds protocol
    // traits. Filtering by namespace rather than by trait-ness would drop it while members still
    // referenced its schema.
    val out = generate(
      """|$version: "2"
         |namespace test
         |
         |use alloy#UUID
         |
         |structure Holder {
         |  @required
         |  id: UUID
         |}
         |""".stripMargin
    )

    assert(clue(out).contains("export const UUIDSchema"))
    assert(out.contains("id: UUIDSchema,"))
  }

  // --------------------------------------------------------------------------
  // Cross-namespace name deconfliction
  // --------------------------------------------------------------------------

  test("a name owned by one shape is emitted bare") {
    val out = generate(
      """|$version: "2"
         |namespace com.example.a
         |
         |string ProfileId
         |""".stripMargin
    )

    assert(clue(out).contains("export const ProfileIdSchema = z.string().brand<'ProfileId'>()"))
  }

  test("same name in two namespaces: both are qualified, neither keeps the bare name") {
    val out = generateAll(
      """|$version: "2"
         |namespace com.example.a
         |
         |string ProfileId
         |
         |structure Holder {
         |  @required
         |  mine: ProfileId
         |
         |  @required
         |  theirs: com.example.b#ProfileId
         |}
         |""".stripMargin,
      """|$version: "2"
         |namespace com.example.b
         |
         |string ProfileId
         |""".stripMargin,
    )

    assert(clue(out).contains("export const com_example_a_ProfileIdSchema"))
    assert(out.contains("export const com_example_b_ProfileIdSchema"))
    // Neither may claim the bare name — that is what makes the scheme stable when a namespace
    // is added later.
    assert(!out.contains("export const ProfileIdSchema"))
    assert(out.contains("mine: com_example_a_ProfileIdSchema,"))
    assert(out.contains("theirs: com_example_b_ProfileIdSchema,"))
  }

  test("colliding aliases get distinct brands, so they are not mutually assignable") {
    val out = generateAll(
      """|$version: "2"
         |namespace com.example.a
         |
         |string ProfileId
         |
         |structure Holder {
         |  @required
         |  theirs: com.example.b#ProfileId
         |}
         |""".stripMargin,
      """|$version: "2"
         |namespace com.example.b
         |
         |string ProfileId
         |""".stripMargin,
    )

    assert(clue(out).contains("brand<'com_example_a_ProfileId'>()"))
    assert(out.contains("brand<'com_example_b_ProfileId'>()"))
  }

  // --- transformPath ---------------------------------------------------------

  /** An extension built from a plain function, so a test can state just the rewrite. */
  private def pathExt(
    f: (ServiceShape, OperationShape, List[PathSegment]) => List[PathSegment]
  ): List[TsCodegenExtension] =
    List(
      new TsCodegenExtension {
        override def transformPath(
          service: ServiceShape,
          operation: OperationShape,
          path: List[PathSegment],
        ): List[PathSegment] = f(service, operation, path)
      }
    )

  /** Prepends literal segments to every operation, ignoring which service it belongs to. */
  private def prefixExt(segments: String*): List[TsCodegenExtension] = pathExt((_, _, path) =>
    segments.map(PathSegment.Literal(_)).toList ++ path
  )

  private val prefixModel =
    """|$version: "2"
       |namespace com.example
       |
       |service Svc {
       |  version: "v1"
       |  operations: [GetThing]
       |}
       |
       |@http(method: "GET", uri: "/things/{id}")
       |operation GetThing {
       |  input := {
       |    @required
       |    @httpLabel
       |    id: String
       |  }
       |  output := {
       |    name: String
       |  }
       |}
       |""".stripMargin

  private val twoServiceModel =
    """|$version: "2"
       |namespace com.example
       |
       |service Public {
       |  version: "v1"
       |  operations: [GetThing]
       |}
       |
       |service Internal {
       |  version: "v2"
       |  operations: [GetOther]
       |}
       |
       |@http(method: "GET", uri: "/things/{id}")
       |operation GetThing {
       |  input := {
       |    @required
       |    @httpLabel
       |    id: String
       |  }
       |  output := {
       |    name: String
       |  }
       |}
       |
       |@http(method: "GET", uri: "/others")
       |operation GetOther {
       |  output := {
       |    name: String
       |  }
       |}
       |""".stripMargin

  test("no extensions leave the @http uri untouched") {
    val out = generate(prefixModel)
    assert(clue(out).contains("const url = `/things/${encodeURIComponent(String(input.id))}`"))
  }

  test("an extension can prepend literal segments to the uri") {
    val out = generate(prefixModel, extensions = prefixExt("internal", "v1"))
    assert(
      clue(out).contains(
        "const url = `/internal/v1/things/${encodeURIComponent(String(input.id))}`"
      )
    )
  }

  test("a rewritten path reaches the mock router as segments, so mocks still match") {
    val out = generate(prefixModel, extensions = prefixExt("internal", "v1"))
    assert(
      clue(out).contains(
        "segments: [{ literal: 'internal' }, { literal: 'v1' }, { literal: 'things' }, { label: 'id' }],"
      )
    )
  }

  test("an extension sees the service, so two services in one run can differ") {
    // The reason this is an extension and not a setting: one codegen run emits
    // both services, and they are mounted differently.
    val out = generate(
      twoServiceModel,
      extensions = pathExt((service, _, path) =>
        if (service.getId.getName == "Internal")
          PathSegment.Literal("internal") :: PathSegment.Literal(service.getVersion) :: path
        else
          path
      ),
    )
    assert(clue(out).contains("const url = `/things/${encodeURIComponent(String(input.id))}`"))
    assert(clue(out).contains("const url = `/internal/v2/others`"))
  }

  test("an extension sees the operation") {
    val out = generate(
      prefixModel,
      extensions = pathExt((_, operation, path) =>
        PathSegment.Literal(operation.getId.getName) :: path
      ),
    )
    assert(clue(out).contains("const url = `/GetThing/things/"))
  }

  test("an extension can rewrite an operation whose uri is just /") {
    val out = generate(
      """|$version: "2"
         |namespace com.example
         |
         |service Svc {
         |  version: "v1"
         |  operations: [Ping]
         |}
         |
         |@http(method: "GET", uri: "/")
         |operation Ping {
         |  output := {
         |    ok: Boolean
         |  }
         |}
         |""".stripMargin,
      extensions = prefixExt("internal"),
    )
    assert(clue(out).contains("const url = `/internal`"))
  }

  test("an extension returning Nil means the root path") {
    val out = generate(
      """|$version: "2"
         |namespace com.example
         |
         |service Svc {
         |  version: "v1"
         |  operations: [Ping]
         |}
         |
         |@http(method: "GET", uri: "/ping")
         |operation Ping {
         |  output := {
         |    ok: Boolean
         |  }
         |}
         |""".stripMargin,
      extensions = pathExt((_, _, _) => Nil),
    )
    assert(clue(out).contains("const url = `/`"))
    assert(clue(out).contains("segments: [],"))
  }

  test("an extension can reorder and drop segments") {
    val out = generate(
      prefixModel,
      extensions = pathExt((_, _, path) => path.reverse),
    )
    assert(clue(out).contains("const url = `/${encodeURIComponent(String(input.id))}/things`"))
    assert(clue(out).contains("segments: [{ label: 'id' }, { literal: 'things' }],"))
  }

  test("extensions compose, each seeing the previous one's result") {
    val out = generate(
      prefixModel,
      extensions = prefixExt("v1") ++ prefixExt("internal"),
    )
    assert(clue(out).contains("const url = `/internal/v1/things/"))
  }

  test("a label an extension invents is rejected, naming it") {
    val e = intercept[Exception](
      generate(
        prefixModel,
        extensions = pathExt((_, _, path) => PathSegment.Label("nope") :: path),
      )
    )
    assert(clue(e.getMessage).contains("nope"))
    assert(clue(e.getMessage).contains("@httpLabel"))
  }

  test("a literal containing a slash is rejected — the mock router would never match it") {
    val e = intercept[Exception](
      generate(prefixModel, extensions = prefixExt("internal/v1"))
    )
    assert(clue(e.getMessage).contains("internal/v1"))
    assert(clue(e.getMessage).contains("GetThing"))
  }

}
