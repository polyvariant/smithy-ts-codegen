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
import software.amazon.smithy.protocoltests.traits.eventstream.EventStreamTestCase
import software.amazon.smithy.protocoltests.traits.eventstream.EventStreamTestsTrait
import software.amazon.smithy.model.traits.StreamingTrait
import software.amazon.smithy.model.traits.TimestampFormatTrait

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.regex.Pattern
import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Renders the HTTP protocol compliance suites into what `compliance/` runs: per suite, the
  * TypeScript the codegen emits for the suite's services (`generated.ts`) and every client-side
  * `@httpRequestTests` / `@httpResponseTests` case that applies to them (`cases.json`).
  *
  * Three suites, all loaded from the classpath:
  *
  *   - `alloy` — `alloy-protocol-tests`, written for `alloy#simpleRestJson` itself.
  *   - `restJson1` — smithy's own `aws.protocols#restJson1` tests, restricted to the ones alloy
  *     declares as applying to `simpleRestJson` (the `alloySimpleRestJsonBorrowedTests` rules
  *     shipped in `alloy-protocol-tests`), interpreted exactly as smithy4s interprets them — see
  *     [[AllowRule]]. So this is the set smithy4s runs, no more and no less.
  *   - `ndjsonRestJson` — `smithy4s-ndjson-protocol-tests`, for `@ndjsonRestJson`: the streaming
  *     protocol, including its `@eventStreamTests` (see [[eventCases]]).
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
    includeCase: TestCase => Boolean,
  )

  /** One entry of alloy's allow or disallow list, matched as smithy4s's `AllowRules` does: the id
    * is a glob where `*` matches any run of characters (`**` is no different), and `appliesTo` /
    * `testType`, when given, narrow it to client or server, request or response cases.
    */
  final case class AllowRule(id: String, appliesTo: Option[String], testType: Option[String]) {

    private val pattern: Pattern = Pattern.compile(
      id.split("\\*", -1)
        .map(part =>
          if (part.isEmpty)
            ""
          else
            Pattern.quote(part)
        )
        .mkString(".*")
    )

    /** Only client cases are rendered here, so a rule for the server side never matches. */
    def matches(c: TestCase): Boolean =
      pattern.matcher(c.testCase.getId).matches &&
        appliesTo.forall(_ == "client") &&
        testType.forall(_ == c.kind)

  }

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
  private val NdjsonRestJson = ShapeId.from("org.polyvariant.ndjson#ndjsonRestJson")
  private val ServerSentEvents = ShapeId.from("org.polyvariant.ndjson#serverSentEvents")
  private val NdjsonComplianceNamespace = "org.polyvariant.ndjson.compliance"

  def suites(model: Model): List[Suite] = {
    val borrowed = borrowedRestJson1Tests(model)
    List(
      Suite(
        name = "alloy",
        protocol = SimpleRestJson,
        select =
          _.getServiceShapes.asScala.toList.filter(_.getId.getNamespace.startsWith("alloy.test")),
        // smithy4s runs every alloy case.
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
              .filter(op => operationCases(m, service, op).exists(borrowed))
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
      Suite(
        name = "ndjsonRestJson",
        protocol = NdjsonRestJson,
        select =
          _.getServiceShapes
            .asScala
            .toList
            .filter(_.getId.getNamespace == NdjsonComplianceNamespace),
        includeCase = _ => true,
      ),
    )
  }

  /** smithy4s's `AllowRules.shouldRun`: a disallow rule wins, then an allow rule admits. */
  private def borrowedRestJson1Tests(model: Model): TestCase => Boolean = {
    val rules = model
      .getMetadataProperty("alloySimpleRestJsonBorrowedTests")
      .toScala
      .flatMap(_.expectObjectNode.getObjectMember(RestJson1.toString).toScala)
    def list(name: String): List[AllowRule] =
      rules
        .flatMap(_.getArrayMember(name).toScala)
        .toList
        .flatMap(_.getElements.asScala)
        .map(_.expectObjectNode)
        .map { r =>
          AllowRule(
            r.expectStringMember("id").getValue,
            r.getStringMember("appliesTo").toScala.map(_.getValue),
            r.getStringMember("testType").toScala.map(_.getValue),
          )
        }
    val allow = list("allowList")
    val disallow = list("disallowList")
    c => !disallow.exists(_.matches(c)) && allow.exists(_.matches(c))
  }

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
        suite.includeCase(c)
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
    // A streamed blob stays its text: the TS API takes and returns it as a stream of bytes, which
    // the runner makes from (and reads back into) that text.
    val streamedBlob = c
      .error
      .fold(streamOf(model, paramsShape.getId))(_ => None)
      .filter(_.encoding == "binary")
    val converted =
      raw
        .getMember("params")
        .toScala
        .fold(raw) { p =>
          val ts = toTsValue(model, p, paramsShape).expectObjectNode
          val kept =
            streamedBlob.fold(ts) { s =>
              p.expectObjectNode.getMember(s.member).toScala.fold(ts)(ts.withMember(s.member, _))
            }
          raw.withMember("params", kept)
        }
    val builder = Node
      .objectNodeBuilder()
      .withMember("id", c.testCase.getId)
      .withMember("kind", c.kind)
      .withMember("service", c.service.getId.getName)
      .withMember("operation", c.operation.getId.getName)
      .withMember("error", c.error.fold(Node.nullNode())(e => Node.from(e.getId.getName)))
    streamsNode(model, c.operation)
      .filter(_ => c.error.isEmpty)
      .fold(builder)(builder.withMember("streams", _))
      .withMember("case", converted)
      .build()
  }

  /** The member of a structure that is streamed, and how the protocol frames it. */
  final case class Stream(member: String, encoding: String, terminator: Option[String]) {

    def toNode: Node =
      Node.objectNode(
        ListMap
          .from(
            List(
              Node.from("member") -> Node.from(member),
              Node.from("encoding") -> Node.from(encoding),
            ) ++
              terminator.map(t => Node.from("terminator") -> Node.from(t))
          )
          .asJava
      )

  }

  def streamOf(model: Model, structure: ShapeId): Option[Stream] =
    model.getShape(structure).toScala.toList.flatMap(_.members.asScala).collectFirst {
      case m if model.expectShape(m.getTarget).hasTrait(classOf[StreamingTrait]) =>
        val target = model.expectShape(m.getTarget)
        val sse = target.findTrait(ServerSentEvents).toScala
        val encoding =
          if (target.isBlobShape)
            "binary"
          else if (sse.isDefined)
            "sse"
          else
            "ndjson"
        Stream(
          m.getMemberName,
          encoding,
          sse.flatMap(
            _.toNode.expectObjectNode.getStringMember("terminator").toScala.map(_.getValue)
          ),
        )
    }

  /** `{input, output}`: what an operation streams each way, or nothing if it streams neither. */
  private def streamsNode(model: Model, op: OperationShape): Option[Node] = {
    val in = streamOf(model, op.getInputShape)
    val out = streamOf(model, op.getOutputShape)
    Option.when(in.isDefined || out.isDefined)(
      obj(
        List(
          Node.from("input") -> in.fold(Node.nullNode())(_.toNode),
          Node.from("output") -> out.fold(Node.nullNode())(_.toNode),
        )
      )
    )
  }

  /** Every client-side `@eventStreamTests` case for the suite's protocol, with its operation.
    *
    * Rendered like the HTTP cases, as kind `events`: `initialRequestParams`,
    * `initialResponseParams` and each event's `params` in the TS API's form, `bytes` left as the
    * model has them (base64).
    */
  def eventCases(model: Model, suite: Suite)
    : List[(ServiceShape, OperationShape, EventStreamTestCase, ObjectNode)] = {
    val index = TopDownIndex.of(model)
    model.getServiceShapes.asScala.toList.sortBy(_.getId.toString).flatMap { service =>
      index.getContainedOperations(service).asScala.toList.sortBy(_.getId.toString).flatMap { op =>
        // A case has no node form of its own; the trait's is its cases', in order.
        op.getTrait(classOf[EventStreamTestsTrait])
          .toScala
          .toList
          .flatMap(t => t.getTestCases.asScala.zip(t.toNode.expectArrayNode.getElements.asScala))
          .filter((tc, _) =>
            tc.getProtocol == suite.protocol && !tc.getAppliesTo.toScala.contains(AppliesTo.SERVER)
          )
          .map((tc, node) => (service, op, tc, node.expectObjectNode))
      }
    }
  }

  def eventCaseToNode(
    model: Model,
    service: ServiceShape,
    op: OperationShape,
    tc: EventStreamTestCase,
    raw: ObjectNode,
  ): Node = {
    val input = model.expectShape(op.getInputShape)
    val output = model.expectShape(op.getOutputShape)
    def union(structure: Shape): Option[Shape] =
      streamOf(model, structure.getId).map(s =>
        model.expectShape(structure.getMember(s.member).get.getTarget)
      )
    val eventNodes = raw.getArrayMember("events").toScala.toList.flatMap(_.getElements.asScala)
    val events = tc.getEvents.asScala.toList.zip(eventNodes).map { (event, node) =>
      val shape = union(
        if (event.getType.toString == "request")
          input
        else
          output
      )
      val eventNode = node.expectObjectNode
      eventNode
        .getMember("params")
        .toScala
        .flatMap(p => shape.map(s => eventNode.withMember("params", toTsValue(model, p, s))))
        .getOrElse(eventNode)
    }
    val converted = List[(String, Shape)](
      "initialRequestParams" -> input,
      "initialResponseParams" -> output,
    ).foldLeft(raw) { case (acc, (name, shape)) =>
      acc.getMember(name).toScala.fold(acc)(p => acc.withMember(name, toTsValue(model, p, shape)))
    }.withMember("events", Node.fromNodes(events.asJava))
      .withMember(
        "expectation",
        if (tc.getExpectation.isFailure)
          "failure"
        else
          "success",
      )
    Node
      .objectNodeBuilder()
      .withMember("id", tc.getId)
      .withMember("kind", "events")
      .withMember("service", service.getId.getName)
      .withMember("operation", op.getId.getName)
      .withMember("error", Node.nullNode())
      .withMember("streams", streamsNode(model, op).getOrElse(Node.nullNode()))
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
  def toTsValue(model: Model, node: Node, shape: Shape): Node = toTsValue(model, node, shape, None)

  /** `memberFormat` is the `@timestampFormat` of the member being read, which wins over its
    * target's. `@httpRequestTests` always writes a timestamp as epoch seconds, but
    * `@eventStreamTests` writes it in its format — Smithy's validators insist on both.
    */
  private def toTsValue(model: Model, node: Node, shape: Shape, memberFormat: Option[String])
    : Node =
    shape match {
      case m: MemberShape =>
        toTsValue(
          model,
          node,
          model.expectShape(m.getTarget),
          m.getTrait(classOf[TimestampFormatTrait]).toScala.map(_.getValue),
        )
      case _ if node.isNullNode => node
      case t: TimestampShape    =>
        val format = memberFormat.orElse(
          t.getTrait(classOf[TimestampFormatTrait]).toScala.map(_.getValue)
        )
        obj(List(Node.from("$date") -> epochSeconds(node, format)))
      case _: BlobShape => base64(node)
      case l: ListShape =>
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

  private def epochSeconds(node: Node, format: Option[String]): Node =
    node.asStringNode.toScala.fold(node) { s =>
      val instant =
        if (format.contains(TimestampFormatTrait.HTTP_DATE))
          ZonedDateTime.parse(s.getValue, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant
        else
          Instant.parse(s.getValue)
      Node.from(instant.getEpochSecond)
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
      val json = Node.fromNodes(
        (cases(narrowed, suite).map(caseToNode(narrowed, _)) ++
          eventCases(narrowed, suite).map((service, op, tc, node) =>
            eventCaseToNode(narrowed, service, op, tc, node)
          )).asJava
      )
      val _ = Files.writeString(dir.resolve("cases.json"), Node.prettyPrintJson(json) + "\n")
    }
  }

}
