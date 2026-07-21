package org.polyvariant.smithy.ts

import software.amazon.smithy.model.Model

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

}
