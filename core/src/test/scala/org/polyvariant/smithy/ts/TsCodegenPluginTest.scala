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

import software.amazon.smithy.model.Model

import scala.jdk.CollectionConverters.*

class TsCodegenPluginTest extends munit.FunSuite {

  private def model(smithy: String): Model =
    Model
      .assembler()
      .discoverModels(this.getClass.getClassLoader)
      .addUnparsedModel("test.smithy", smithy)
      .assemble()
      .unwrap()

  private def generate(smithy: String, exclude: Set[String] = Set.empty): String =
    TsCodegenPlugin.generate(model(smithy), exclude)

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

}
