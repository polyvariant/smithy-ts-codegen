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

package org.polyvariant.smithy.ts.compliance

import org.polyvariant.smithy.ts.TsCodegenPlugin
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.knowledge.TopDownIndex
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.node.ObjectNode
import software.amazon.smithy.model.node.StringNode
import software.amazon.smithy.model.shapes.BlobShape
import software.amazon.smithy.model.shapes.ListShape
import software.amazon.smithy.model.shapes.MapShape
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.TimestampShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.transform.ModelTransformer
import software.amazon.smithy.protocoltests.traits.AppliesTo
import software.amazon.smithy.protocoltests.traits.HttpMessageTestCase
import software.amazon.smithy.protocoltests.traits.HttpRequestTestsTrait
import software.amazon.smithy.protocoltests.traits.HttpResponseTestsTrait

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.Base64
import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Renders the HTTP protocol compliance suites into what `compliance/` runs: per suite, the
  * TypeScript the codegen emits for the suite's services (`generated.ts`) and every client-side
  * `@httpRequestTests` / `@httpResponseTests` case that applies to them (`cases.json`).
  *
  * Two suites, both loaded from the classpath:
  *
  *   - `alloy` — `alloy-protocol-tests`, written for `alloy#simpleRestJson` itself.
  *   - `restJson1` — smithy's own `aws.protocols#restJson1` tests, restricted to the ones alloy
  *     declares as applying to `simpleRestJson` (the `alloySimpleRestJsonBorrowedTests` allow list
  *     shipped in `alloy-protocol-tests`). This is the same set smithy4s runs.
  *
  * A case's `params` are written in Smithy's node form, which is not always how the TypeScript API
  * spells a value. They are rewritten here, with the model at hand, so the runner can stay
  * schema-unaware — see [[toTsValue]].
  *
  * Usage: `ComplianceCodegen <outDir>`
  */
object ComplianceCodegen {

  final case class Suite(
    name: String,
    protocol: ShapeId,
    // Narrows the loaded model to the services (and operations) this suite covers.
    select: Model => List[ServiceShape],
    includeCase: String => Boolean,
  )

  final case class TestCase(
    kind: String,
    service: ServiceShape,
    operation: OperationShape,
    error: Option[StructureShape],
    testCase: HttpMessageTestCase,
  )

  private val SimpleRestJson = ShapeId.from("alloy#simpleRestJson")
  private val RestJson1 = ShapeId.from("aws.protocols#restJson1")
  private val RestJsonService = ShapeId.from("aws.protocoltests.restjson#RestJson")
  private val Discriminated = ShapeId.from("alloy#discriminated")
  private val JsonUnknown = ShapeId.from("alloy#jsonUnknown")

  /** Allow-listed operations the codegen cannot render at all — generating them fails the whole
    * file, so they are left out of the suite rather than listed as known failures.
    */
  private val unrenderable: Map[ShapeId, String] = Map(
    ShapeId.from("aws.protocoltests.restjson#RecursiveShapes") -> "recursive shapes are unsupported"
  )

  def suites(model: Model): List[Suite] = {
    val borrowed = borrowedRestJson1Tests(model)
    List(
      Suite(
        name = "alloy",
        protocol = SimpleRestJson,
        select =
          _.getServiceShapes.asScala.toList.filter(_.getId.getNamespace.startsWith("alloy.test")),
        includeCase = _ => true,
      ),
      Suite(
        name = "restJson1",
        protocol = RestJson1,
        select =
          m => {
            val service = m.expectShape(RestJsonService, classOf[ServiceShape])
            val keep = TopDownIndex
              .of(m)
              .getContainedOperations(service)
              .asScala
              .toList
              .filter(op => !unrenderable.contains(op.getId))
              .filter(op => operationCases(m, service, op).exists(c => borrowed(c.testCase.getId)))
            List(
              service
                .toBuilder
                .operations(keep.map(_.getId).asJava)
                .resources(Set.empty.asJava)
                .build()
            )
          },
        includeCase = borrowed,
      ),
    )
  }

  private def borrowedRestJson1Tests(model: Model): Set[String] =
    model
      .getMetadataProperty("alloySimpleRestJsonBorrowedTests")
      .toScala
      .flatMap(_.expectObjectNode.getObjectMember(RestJson1.toString).toScala)
      .flatMap(_.getArrayMember("allowList").toScala)
      .toList
      .flatMap(_.getElements.asScala)
      .map(_.expectObjectNode.expectStringMember("id").getValue)
      .toSet

  /** The suite's services and nothing else: every other service is dropped, and so is every shape
    * that is then unreachable — the codegen emits all shapes it is given.
    */
  def narrow(model: Model, services: List[ServiceShape]): Model = {
    val transformer = ModelTransformer.create()
    val keepIds = services.map(_.getId).toSet
    val others = model.getServiceShapes.asScala.filterNot(s => keepIds.contains(s.getId))
    val withoutOthers = transformer.removeShapes(model, others.toList.asJava)
    transformer.removeUnreferencedShapes(transformer.replaceShapes(withoutOthers, services.asJava))
  }

  private def operationCases(
    model: Model,
    service: ServiceShape,
    op: OperationShape,
  ): List[TestCase] = {
    val requests = op
      .getTrait(classOf[HttpRequestTestsTrait])
      .toScala
      .toList
      .flatMap(_.getTestCases.asScala)
      .map(TestCase("request", service, op, None, _))
    val responses = op
      .getTrait(classOf[HttpResponseTestsTrait])
      .toScala
      .toList
      .flatMap(_.getTestCases.asScala)
      .map(TestCase("response", service, op, None, _))
    val errors = op.getErrors(service).asScala.toList.sortBy(_.toString).flatMap { id =>
      val error = model.expectShape(id, classOf[StructureShape])
      error
        .getTrait(classOf[HttpResponseTestsTrait])
        .toScala
        .toList
        .flatMap(_.getTestCases.asScala)
        .map(TestCase("response", service, op, Some(error), _))
    }
    requests ++ responses ++ errors
  }

  /** Every client-side case for the suite's protocol, once each. An error's cases are attached to
    * the first operation (by shape id) that can throw it — any one of them exercises the same
    * decoding.
    */
  def cases(model: Model, suite: Suite): List[TestCase] = {
    val index = TopDownIndex.of(model)
    model
      .getServiceShapes
      .asScala
      .toList
      .sortBy(_.getId.toString)
      .flatMap { service =>
        index
          .getContainedOperations(service)
          .asScala
          .toList
          .sortBy(_.getId.toString)
          .flatMap(operationCases(model, service, _))
      }
      .filter { c =>
        c.testCase.getProtocol == suite.protocol &&
        !c.testCase.getAppliesTo.toScala.contains(AppliesTo.SERVER) &&
        suite.includeCase(c.testCase.getId)
      }
      .distinctBy(c => (c.error.map(_.getId), c.kind, c.testCase.getId))
  }

  def caseToNode(model: Model, c: TestCase): Node = {
    val paramsShape: Shape = c.error.getOrElse {
      val id =
        if (c.kind == "request")
          c.operation.getInputShape
        else
          c.operation.getOutputShape
      model.expectShape(id)
    }
    val raw = c.testCase.toNode.expectObjectNode
    val converted =
      raw
        .getMember("params")
        .toScala
        .fold(raw)(p => raw.withMember("params", toTsValue(model, p, paramsShape)))
    Node
      .objectNodeBuilder()
      .withMember("id", c.testCase.getId)
      .withMember("kind", c.kind)
      .withMember("service", c.service.getId.getName)
      .withMember("operation", c.operation.getId.getName)
      .withMember("error", c.error.fold(Node.nullNode())(e => Node.from(e.getId.getName)))
      .withMember("case", converted)
      .build()
  }

  /** Rewrites a Smithy node value into the value the generated TypeScript API takes and returns,
    * where the two differ:
    *
    *   - timestamps → `{"$date": <epoch seconds>}`, revived as a `Date` by the runner (JSON has no
    *     date type);
    *   - blobs → base64 (the TS API carries a blob as its base64 string);
    *   - `@discriminated` union values → flattened, discriminator alongside the member's fields;
    *   - `@jsonUnknown` union members → the raw unknown value, which is how an open union holds it.
    */
  def toTsValue(model: Model, node: Node, shape: Shape): Node =
    shape match {
      case m: MemberShape       => toTsValue(model, node, model.expectShape(m.getTarget))
      case _ if node.isNullNode => node
      case _: TimestampShape    => obj(List(Node.from("$date") -> epochSeconds(node)))
      case _: BlobShape         => base64(node)
      case l: ListShape         =>
        Node.fromNodes(
          node.expectArrayNode.getElements.asScala.map(toTsValue(model, _, l.getMember)).asJava
        )
      case m: MapShape =>
        obj(node.expectObjectNode.getMembers.asScala.toList.map { case (k, v) =>
          k -> toTsValue(model, v, m.getValue)
        })
      case u: UnionShape     => unionValue(model, node, u)
      case s: StructureShape =>
        obj(node.expectObjectNode.getMembers.asScala.toList.map { case (k, v) =>
          k -> s.getMember(k.getValue).toScala.fold(v)(toTsValue(model, v, _))
        })
      case _ => node
    }

  private def unionValue(model: Model, node: Node, union: UnionShape): Node =
    node.expectObjectNode.getMembers.asScala.headOption.fold(node) { case (key, value) =>
      union.getMember(key.getValue).toScala match {
        case Some(member) if member.hasTrait(JsonUnknown) => value
        case Some(member)                                 =>
          val converted = toTsValue(model, value, member)
          union.findTrait(Discriminated).toScala match {
            case Some(t) =>
              converted
                .expectObjectNode
                .withMember(t.toNode.expectStringNode.getValue, Node.from(key.getValue))
            case None => obj(List(key -> converted))
          }
        case None => node
      }
    }

  private def epochSeconds(node: Node): Node =
    node.asStringNode.toScala.fold(node) { s =>
      Node.from(Instant.parse(s.getValue).getEpochSecond)
    }

  private def base64(node: Node): Node =
    node.asStringNode.toScala.fold(node) { s =>
      Node.from(Base64.getEncoder.encodeToString(s.getValue.getBytes(StandardCharsets.UTF_8)))
    }

  private def obj(entries: List[(StringNode, Node)]): ObjectNode =
    Node.objectNode(ListMap.from(entries).asJava)

  def main(args: Array[String]): Unit =
    args.toList match {
      case out :: Nil => writeAllUnsafe(Paths.get(out))
      case _          =>
        Console.err.println("usage: ComplianceCodegen <outDir>")
        sys.exit(2)
    }

  private def writeAllUnsafe(outDir: Path): Unit = {
    val model = Model
      .assembler(getClass.getClassLoader)
      .discoverModels(getClass.getClassLoader)
      .assemble()
      .unwrap()
    suites(model).foreach { suite =>
      val narrowed = narrow(model, suite.select(model))
      val dir = outDir.resolve(suite.name)
      val _ = Files.createDirectories(dir)
      val _ = Files.writeString(
        dir.resolve("generated.ts"),
        TsCodegenPlugin.generate(narrowed, Set.empty),
      )
      val json = Node.fromNodes(cases(narrowed, suite).map(caseToNode(narrowed, _)).asJava)
      val _ = Files.writeString(dir.resolve("cases.json"), Node.prettyPrintJson(json) + "\n")
    }
  }

}
