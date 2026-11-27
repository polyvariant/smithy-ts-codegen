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

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.Stream
import org.http4s.Header
import org.http4s.Headers
import org.http4s.HttpRoutes
import org.http4s.Method
import org.http4s.Request
import org.http4s.Uri
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.polyvariant.ndjson.NdjsonRestJson
import org.polyvariant.ndjson.http4s.NdjsonRestJsonBuilder
import org.polyvariant.ndjson.http4s.WithStreamedIO
import org.typelevel.ci.CIString
import smithy4s.Blob
import smithy4s.Document
import smithy4s.Endpoint
import smithy4s.Hints
import smithy4s.Service
import smithy4s.ShapeId as S4ShapeId
import smithy4s.compliancetests.internals.CanonicalSmithyDecoder
import smithy4s.compliancetests.internals.eq.EqSchemaVisitor
import smithy4s.dynamic.DynamicSchemaIndex
import smithy4s.json.Json
import smithy4s.kinds.PolyFunction5
import smithy4s.schema.Primitive
import smithy4s.schema.Schema
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.node.ObjectNode
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.protocoltests.traits.HttpRequestTestCase
import software.amazon.smithy.protocoltests.traits.HttpResponseTestCase
import software.amazon.smithy.protocoltests.traits.eventstream.Event
import software.amazon.smithy.protocoltests.traits.eventstream.EventStreamTestCase
import software.amazon.smithy.protocoltests.traits.eventstream.EventType

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** The ndjsonRestJson suite, served by smithy4s-ndjson's `NdjsonRestJsonBuilder` — the real server
  * the generated client is meant to talk to — through the same `/__roundtrip` protocol as
  * [[RoundTrip]].
  *
  * Its cases' `params` aren't what this compares against. `@eventStreamTests` writes timestamps in
  * their member's format where every other case uses epoch seconds, and smithy4s's canonical reader
  * drops `@nullable`; reading them back faithfully would mean a second copy of the protocol. Each
  * case states its exact bytes, though, so the server reads those instead:
  *
  *   - what the client sends is compared with what the server decodes from the case's own request
  *     (`initialRequest` and the request events, or an HTTP case's request), run through the same
  *     routes when the case is armed;
  *   - what the server answers with is each response event's `body`, decoded with the protocol's
  *     own JSON codec, so the client reads values that smithy4s-ndjson encoded.
  *
  * An HTTP response case is answered as [[RoundTrip]] answers one, through an endpoint that takes
  * no input, here with the operation's stream attached so a streamed blob is written too.
  */
object NdjsonRoundTrip {

  sealed trait Case {
    def kind: String
    def id: String
    def service: ServiceShape
    def operation: OperationShape
    def error: Option[String]
  }

  final case class RequestCase(
    service: ServiceShape,
    operation: OperationShape,
    tc: HttpRequestTestCase,
  ) extends Case {
    def kind = "request"
    def id: String = tc.getId
    def error: Option[String] = None
  }

  final case class ResponseCase(
    service: ServiceShape,
    operation: OperationShape,
    error: Option[String],
    tc: HttpResponseTestCase,
  ) extends Case {
    def kind = "response"
    def id: String = tc.getId
  }

  final case class EventsCase(
    service: ServiceShape,
    operation: OperationShape,
    tc: EventStreamTestCase,
  ) extends Case {
    def kind = "events"
    def id: String = tc.getId
    def error: Option[String] = None
  }

  /** What an operation's handler was handed. */
  private final case class Recorded(endpoint: S4ShapeId, input: Any, stream: List[Any])

  /** What a handler answers with, its types erased like the handler's own. */
  private final case class Answer(output: Any, stream: List[Any])

  private enum Armed {

    /** Record what `endpoint` is handed, check it against `check` once there is something to check
      * against, and answer with `answer` — or fail on purpose without one.
      */
    case Exchange(
      endpoint: S4ShapeId,
      answer: Option[Answer],
      check: Option[Recorded => Either[String, Unit]],
    )

    /** Answer anything with `request` run against `routes`. */
    case Respond(routes: HttpRoutes[IO], request: Request[IO])

  }

  private object ShortCircuit extends Exception("short-circuited on purpose", null, false, false)

  /** smithy4s-ndjson's codec for a streamed value: the JSON payload codecs, under the protocol's
    * own hint mask.
    */
  private val jsonCodecs = Json
    .payloadCodecs
    .configureJsoniterCodecCompiler(_.withHintMask(NdjsonRestJson.protocol.hintMask))

  def serve(model: Model, cases: List[Case]): Resource[IO, Uri] = {
    val dsi = DynamicSchemaIndex.loadModel(model)
    for {
      armed <- Resource.eval(Ref[IO].of(Option.empty[Armed]))
      recorded <- Resource.eval(Ref[IO].of(Option.empty[Recorded]))
      loaded = dsi.allServices.toList.map(w => load(model, w.service, armed, recorded))
      services = loaded.map(_.routes).reduce(_ <+> _)
      app =
        control(model, loaded.map(_.service), cases, services, armed, recorded) <+>
          dispatch(armed, services)
      server <-
        EmberServerBuilder
          .default[IO]
          .withHost(host"127.0.0.1")
          .withPort(port"0")
          .withHttpApp(app.orNotFound)
          .withShutdownTimeout(scala.concurrent.duration.Duration.Zero)
          .build
    } yield Uri.unsafeFromString(s"http://127.0.0.1:${server.address.getPort}")
  }

  /** A service as this serves it: with its streams, and its routes. */
  private final case class Loaded(service: Service[?], routes: HttpRoutes[IO])

  private def load[Alg[_[_, _, _, _, _]]](
    model: Model,
    original: Service[Alg],
    armed: Ref[IO, Option[Armed]],
    recorded: Ref[IO, Option[Recorded]],
  ): Loaded = {
    val service = withStreams(model, original)
    Loaded(
      service,
      NdjsonRestJsonBuilder.routes(handlers(service, armed, recorded))(
        using summon,
        service,
      ),
    )
  }

  /** `original` with its `@streaming` members as streams.
    *
    * smithy4s's dynamic schemas don't stream: a `@streaming` member is an ordinary member of the
    * input or output structure, so a server built from them would read a streamed union as one JSON
    * value and a streamed blob as base64. Each such member is moved out of its structure into the
    * endpoint's `streamedInput` / `streamedOutput`, as smithy4s's code generator does — a blob
    * becoming a stream of bytes that keeps its hints (`@mediaType`) — so that
    * `NdjsonRestJsonBuilder` sees the operation the way it sees generated code.
    */
  private def withStreams[Alg[_[_, _, _, _, _]]](model: Model, original: Service[Alg])
    : Service.Reflective[original.Operation] = {
    def amend[I, E, O, SI, SO](e: original.Endpoint[I, E, O, SI, SO])
      : original.Endpoint[?, ?, ?, ?, ?] = {
      val op = model.expectShape(
        software.amazon.smithy.model.shapes.ShapeId.from(e.id.show),
        classOf[OperationShape],
      )
      val base = e.schema.asInstanceOf[smithy4s.schema.OperationSchema[I, E, O, Any, Any]]
      val withIn =
        streamedField(model, op.getInputShape, e.input).fold(base) { (input, stream) =>
          base.withInput(input).withStreamedInput(stream)
        }
      val schema =
        streamedField(model, op.getOutputShape, e.output).fold(withIn) { (output, stream) =>
          withIn.withOutput(output).withStreamedOutput(stream)
        }
      Endpoint[original.Operation, I, E, O, Any, Any](
        schema,
        (i: I) => e.wrap(i).asInstanceOf[original.Operation[I, E, O, Any, Any]],
      )
    }
    val amended = original.endpoints.toVector.map(e => amend(e))
    new Service.Reflective[original.Operation] {
      def id: S4ShapeId = original.id
      def endpoints: Vector[Endpoint[?, ?, ?, ?, ?]] = amended
        .asInstanceOf[Vector[Endpoint[?, ?, ?, ?, ?]]]
      def input[I, E, O, SI, SO](op: original.Operation[I, E, O, SI, SO]): I = original.input(op)
      def ordinal[I, E, O, SI, SO](op: original.Operation[I, E, O, SI, SO]): Int =
        original.ordinal(op)
      def version: String = original.version
      def hints: Hints = original.hints
    }
  }

  /** `schema` without its `@streaming` member, and that member as a stream — if it has one. The
    * member's slot is left empty when the structure is made: nothing reads it, as it is no longer
    * one of the structure's fields.
    */
  private def streamedField[S](
    model: Model,
    structure: software.amazon.smithy.model.shapes.ShapeId,
    schema: Schema[S],
  ): Option[(Schema[S], smithy4s.schema.StreamingSchema[Any])] =
    ComplianceCodegen.streamOf(model, structure).flatMap { stream =>
      schema match {
        case s: Schema.StructSchema[S] =>
          val index = s.fields.indexWhere(_.label == stream.member)
          Option.when(index >= 0) {
            val target = unwrapOption(s.fields(index).schema)
            // A blob as codegen renders it — a newtype over `Byte` — which is how
            // smithy4s-ndjson tells a streamed blob from a streamed union.
            val element: Schema[?] =
              if (stream.encoding == "binary")
                Schema
                  .byte
                  .withId(target.shapeId)
                  .addHints(target.hints)
                  .biject[Byte](identity[Byte])(identity[Byte])
              else
                target
            val fields = s.fields.patch(index, Nil, 1)
            val make = (values: IndexedSeq[Any]) => s.make(values.patch(index, List(null), 0))
            (
              Schema.StructSchema(s.shapeId, s.hints, fields, make),
              smithy4s.schema.StreamingSchema(stream.member, element.asInstanceOf[Schema[Any]]),
            )
          }
        case _ => None
      }
    }

  private def unwrapOption(schema: Schema[?]): Schema[?] =
    schema match {
      case Schema.OptionSchema(_, underlying) => underlying
      case other                              => other
    }

  /** Every operation of `service`: reads its whole request stream, records it with the input, then
    * answers per the armed case.
    */
  private def handlers[Alg[_[_, _, _, _, _]]](
    service: Service[Alg],
    armed: Ref[IO, Option[Armed]],
    recorded: Ref[IO, Option[Recorded]],
  ): Alg[WithStreamedIO[IO]] =
    service.fromPolyFunction[WithStreamedIO[IO]](
      new PolyFunction5[service.Operation, WithStreamedIO[IO]] {
        def apply[I, E, O, SI, SO](op: service.Operation[I, E, O, SI, SO])
          : Stream[IO, SI] => IO[(O, Stream[IO, SO])] =
          in =>
            for {
              streamed <- in.compile.toList
              _ <- recorded.set(
                Some(Recorded(service.endpoint(op).id, service.input(op), streamed))
              )
              answer <- armed.get.map {
                case Some(Armed.Exchange(_, answer, _)) => answer
                case _                                  => None
              }
              out <-
                answer match {
                  case Some(Answer(output, values)) =>
                    IO.pure((output.asInstanceOf[O], Stream.emits(values.asInstanceOf[List[SO]])))
                  case None => IO.raiseError(ShortCircuit)
                }
            } yield out
      }
    )

  /** While an HTTP response case is armed, every request gets that case's response. */
  private def dispatch(armed: Ref[IO, Option[Armed]], services: HttpRoutes[IO]): HttpRoutes[IO] =
    HttpRoutes[IO] { req =>
      cats.data.OptionT.liftF(armed.get).flatMap {
        case Some(Armed.Respond(routes, synthetic)) => routes(synthetic)
        case _                                      => services(req)
      }
    }

  private def control(
    model: Model,
    loaded: List[Service[?]],
    cases: List[Case],
    services: HttpRoutes[IO],
    armed: Ref[IO, Option[Armed]],
    recorded: Ref[IO, Option[Recorded]],
  ): HttpRoutes[IO] = {
    object ErrorParam extends OptionalQueryParamDecoderMatcher[String]("error")
    HttpRoutes.of[IO] {
      case POST -> Root / "__roundtrip" / "arm" / kind / id :? ErrorParam(error) =>
        cases.find(c => c.kind == kind && c.id == id && c.error == error) match {
          case None    => NotFound(s"no $kind case $id${error.fold("")(e => s" for $e")}")
          case Some(c) =>
            arm(model, loaded, c, services, armed, recorded).flatMap {
              case Left(message) => armed.set(None) *> BadRequest(message)
              case Right(next)   => armed.set(Some(next)) *> recorded.set(None) *> NoContent()
            }
        }
      case GET -> Root / "__roundtrip" / "result" =>
        (armed.get, recorded.get).tupled.flatMap { (a, r) =>
          val result =
            (a, r) match {
              case (_, None) => """{"status":"not-invoked"}"""
              case (Some(Armed.Exchange(_, _, Some(check))), Some(seen)) =>
                check(seen).fold(
                  message =>
                    s"""{"status":"mismatch","message":${Node.printJson(Node.from(message))}}""",
                  _ => """{"status":"ok"}""",
                )
              case _ => """{"status":"ok"}"""
            }
          Ok(result)
        }
    }
  }

  private def arm(
    model: Model,
    loaded: List[Service[?]],
    c: Case,
    services: HttpRoutes[IO],
    armed: Ref[IO, Option[Armed]],
    recorded: Ref[IO, Option[Recorded]],
  ): IO[Either[String, Armed]] =
    loaded
      .find(_.id == RoundTrip.toS4(c.service.getId))
      .toRight(s"no service ${c.service.getId}")
      .flatMap { service =>
        service
          .endpoints
          .find(_.id == RoundTrip.toS4(c.operation.getId))
          .toRight(s"no operation ${c.operation.getId}")
          .map(endpoint => (service, endpoint))
      } match {
      case Left(message)              => IO.pure(Left(message))
      case Right((service, endpoint)) =>
        c match {
          case r: RequestCase =>
            val request = httpRequest(
              r.tc.getMethod,
              r.tc.getUri,
              r.tc.getQueryParams.asScala.toList,
              r.tc.getHeaders.asScala.toMap,
              r.tc.getBody.toScala.getOrElse("").getBytes(UTF_8),
            )
            reference(endpoint, None, request, services, armed, recorded)
          case e: EventsCase   => events(model, endpoint, e, services, armed, recorded)
          case r: ResponseCase => IO.pure(respond(model, service, endpoint, r))
        }
    }

  /** Runs the case's own `request` through the routes, and arms a comparison with what smithy4s
    * decoded from it.
    */
  private def reference[I, E, O, SI, SO](
    endpoint: Endpoint[?, I, E, O, SI, SO],
    answer: Option[Answer],
    request: Request[IO],
    services: HttpRoutes[IO],
    armed: Ref[IO, Option[Armed]],
    recorded: Ref[IO, Option[Recorded]],
  ): IO[Either[String, Armed]] =
    for {
      _ <- armed.set(Some(Armed.Exchange(endpoint.id, answer, None)))
      _ <- recorded.set(None)
      // A handler with no answer fails the request on purpose, after recording it.
      response <- services.orNotFound.run(request).attempt
      body <- response.traverse(_.body.through(fs2.text.utf8.decode).compile.string)
      seen <- recorded.get
    } yield seen match {
      case None =>
        val status = response.fold(e => s"failed: ${e.getMessage}", r => r.status.code.toString)
        Left(s"smithy4s didn't accept the case's own request: $status ${body.getOrElse("")}")
      case Some(expected) =>
        Right(Armed.Exchange(endpoint.id, answer, Some(compare(endpoint, expected))))
    }

  private def compare[I, E, O, SI, SO](endpoint: Endpoint[?, I, E, O, SI, SO], expected: Recorded)
    : Recorded => Either[String, Unit] = {
    val inputEq = EqSchemaVisitor(endpoint.input)
    val showInput = Document.Encoder.fromSchema(endpoint.input)
    val streamCheck: (List[Any], List[Any]) => Option[String] =
      endpoint.streamedInput match {
        case None           => (_, _) => None
        case Some(streamed) =>
          val eq = EqSchemaVisitor(streamed.schema)
          val show = Document.Encoder.fromSchema(streamed.schema)
          (actual, wanted) =>
            val a = actual.asInstanceOf[List[SI]]
            val w = wanted.asInstanceOf[List[SI]]
            Option.when(a.size != w.size || a.zip(w).exists((x, y) => !eq.eqv(x, y)))(
              s"smithy4s decoded the stream ${a.map(show.encode)}, from the case's request ${w.map(show.encode)}"
            )
      }
    actual =>
      if (actual.endpoint != expected.endpoint)
        Left(s"smithy4s routed the request to ${actual.endpoint}, expected ${expected.endpoint}")
      else {
        val input =
          Option.when(
            !inputEq.eqv(actual.input.asInstanceOf[I], expected.input.asInstanceOf[I])
          )(
            s"smithy4s decoded ${showInput.encode(actual.input.asInstanceOf[I])}, from the case's request ${showInput
                .encode(expected.input.asInstanceOf[I])}"
          )
        (input.toList ++ streamCheck(actual.stream, expected.stream).toList) match {
          case Nil      => Right(())
          case problems => Left(problems.mkString("; "))
        }
      }
  }

  /** An event case: the answer is `initialResponseParams` with the response events' values, and the
    * reference is the case's request — `initialRequest`, with the request events as its body.
    */
  private def events[I, E, O, SI, SO](
    model: Model,
    endpoint: Endpoint[?, I, E, O, SI, SO],
    c: EventsCase,
    services: HttpRoutes[IO],
    armed: Ref[IO, Option[Armed]],
    recorded: Ref[IO, Option[Recorded]],
  ): IO[Either[String, Armed]] = {
    val events = c.tc.getEvents.asScala.toList
    val requestEvents = events.filter(_.getType == EventType.REQUEST)
    val responseEvents = events.filter(_.getType == EventType.RESPONSE)
    val initialRequest = c
      .tc
      .getInitialRequest
      .toScala
      .map(_.expectObjectNode)
      .getOrElse(Node.objectNode())
    val inStream = ComplianceCodegen.streamOf(model, c.operation.getInputShape)

    val body: Array[Byte] =
      inStream.fold(stringMember(initialRequest, "body").getOrElse("").getBytes(UTF_8)) { stream =>
        requestEvents.toArray.flatMap(eventBytes(_, stream))
      }
    val request = httpRequest(
      stringMember(initialRequest, "method").getOrElse("POST"),
      stringMember(initialRequest, "uri").getOrElse("/"),
      initialRequest
        .getArrayMember("queryParams")
        .toScala
        .toList
        .flatMap(_.getElements.asScala.map(_.expectStringNode.getValue)),
      initialRequest
        .getObjectMember("headers")
        .toScala
        .toList
        .flatMap(_.getStringMap.asScala.map((k, v) => k -> v.expectStringNode.getValue))
        .toMap,
      body,
    )

    val output =
      CanonicalSmithyDecoder
        .fromSchema(endpoint.output)
        .decode(
          RoundTrip.toDocument(c.tc.getInitialResponseParams.toScala.getOrElse(Node.objectNode()))
        )
        .leftMap(e => s"smithy4s could not read the case's initialResponseParams: ${e.getMessage}")
    val values: Either[String, List[Any]] =
      endpoint.streamedOutput match {
        case None           => Right(Nil)
        case Some(streamed) =>
          val decoder = jsonCodecs.decoders.fromSchema(streamed.schema)
          responseEvents.filter(_.getParams.isPresent).traverse { e =>
            decoder
              .decode(Blob(e.getBody.toScala.getOrElse("")))
              .leftMap(err =>
                s"smithy4s-ndjson could not read the event ${e.getBody.toScala.getOrElse("")}: ${err.getMessage}"
              )
          }
      }
    (output, values).tupled match {
      case Left(message)        => IO.pure(Left(message))
      case Right((out, stream)) =>
        reference(endpoint, Some(Answer(out, stream)), request, services, armed, recorded)
    }
  }

  /** The bytes an event stands for: its `bytes`, or its `body` framed as the protocol writes it. */
  private def eventBytes(e: Event, stream: ComplianceCodegen.Stream): Array[Byte] =
    e.getBytes
      .toScala
      .map(b => Base64.getDecoder.decode(b.toString))
      .getOrElse {
        val data = e.getBody.toScala.getOrElse("")
        val framed =
          if (stream.encoding == "sse")
            data.split("\n", -1).map(line => s"data: $line\n").mkString + "\n"
          else
            s"$data\n"
        framed.getBytes(UTF_8)
      }

  private final case class NoInputOp[I, E, O, SI, SO]()

  /** Answers anything with the case's output, error or streamed blob, through a single endpoint
    * that takes no input — [[RoundTrip]]'s `respond`, with the operation's stream kept.
    */
  private def respond[I, E, O, SI, SO](
    model: Model,
    original: Service[?],
    endpoint: Endpoint[?, I, E, O, SI, SO],
    c: ResponseCase,
  ): Either[String, Armed] = {
    val params = RoundTrip.toDocument(c.tc.getParams)
    val answer: Either[String, IO[(O, Stream[IO, SO])]] =
      c.error match {
        case None =>
          for {
            output <- CanonicalSmithyDecoder
              .fromSchema(endpoint.output)
              .decode(params)
              .leftMap(e => s"smithy4s could not read the case's params: ${e.getMessage}")
            stream <- endpoint.streamedOutput.traverse(s => blobStream(model, c, s.schema, params))
          } yield IO.pure((output, stream.getOrElse(Stream.empty)))
        case Some(name) =>
          endpoint
            .error
            .toRight(s"${endpoint.id} declares no errors")
            .flatMap { errorSchema =>
              errorSchema
                .alternatives
                .find(_.schema.shapeId.name == name)
                .toRight(s"${endpoint.id} declares no error $name")
                .flatMap { alt =>
                  CanonicalSmithyDecoder
                    .fromSchema(alt.schema)
                    .decode(params)
                    .leftMap(e => s"smithy4s could not read the case's params: ${e.getMessage}")
                    .map(a => IO.raiseError(errorSchema.unliftError(alt.inject(a))))
                }
            }
      }
    answer.map { result =>
      val code = endpoint.hints.get(smithy.api.Http).map(_.code).getOrElse(200)
      val http = smithy
        .api
        .Http(
          method = smithy.api.NonEmptyString("GET"),
          uri = smithy.api.NonEmptyString("/"),
          code = code,
        )
      val base = Schema
        .operation(S4ShapeId("custom", "endpoint"))
        .withHints(Hints(http))
        .withOutput(endpoint.output)
        .withErrorOption(endpoint.error)
      val amendedEndpoint =
        endpoint.streamedOutput match {
          case Some(streamed) =>
            Endpoint[NoInputOp, Unit, E, O, Nothing, SO](
              base.withStreamedOutput(streamed),
              (_: Unit) => NoInputOp(),
            )
          case None =>
            Endpoint[NoInputOp, Unit, E, O, Nothing, SO](
              base.asInstanceOf,
              (_: Unit) => NoInputOp(),
            )
        }
      val amended =
        new Service.Reflective[NoInputOp] {
          def id: S4ShapeId = S4ShapeId("custom", "service")
          def endpoints: Vector[Endpoint[?, ?, ?, ?, ?]] = Vector(amendedEndpoint)
          def input[I_, E_, O_, SI_, SO_](op: NoInputOp[I_, E_, O_, SI_, SO_]): I_ = ()
            .asInstanceOf[I_]
          def ordinal[I_, E_, O_, SI_, SO_](op: NoInputOp[I_, E_, O_, SI_, SO_]): Int = 0
          def version: String = original.version
          def hints: Hints = original.hints
        }
      val impl =
        new PolyFunction5[NoInputOp, WithStreamedIO[IO]] {
          def apply[I_, E_, O_, SI_, SO_](op: NoInputOp[I_, E_, O_, SI_, SO_])
            : Stream[IO, SI_] => IO[(O_, Stream[IO, SO_])] =
            _ => result.asInstanceOf[IO[(O_, Stream[IO, SO_])]]
        }
      Armed.Respond(
        NdjsonRestJsonBuilder.routes(impl)(
          using summon,
          amended,
        ),
        Request[IO](Method.GET, Uri.unsafeFromString("/")),
      )
    }
  }

  /** A streamed blob's bytes, from its member's text in `params` — the way Smithy writes a blob in
    * a case.
    */
  private def blobStream[SO](
    model: Model,
    c: ResponseCase,
    schema: Schema[SO],
    params: Document,
  ): Either[String, Stream[IO, SO]] =
    for {
      fromByte <- byteConversion(schema)
      member <- ComplianceCodegen
        .streamOf(model, c.operation.getOutputShape)
        .map(_.member)
        .toRight(s"${c.operation.getId} streams no output member")
    } yield {
      val text =
        params match {
          case Document.DObject(fields) =>
            fields.get(member).collect { case Document.DString(s) => s }
          case _ => None
        }
      Stream.emits(text.getOrElse("").getBytes(UTF_8).toSeq).map(fromByte)
    }

  /** A `@streaming blob` streams bytes: directly, or through a newtype over `Byte`. */
  private def byteConversion[A](schema: Schema[A]): Either[String, Byte => A] =
    schema match {
      case Schema.PrimitiveSchema(_, _, Primitive.PByte) => Right(_.asInstanceOf[A])
      case Schema.BijectionSchema(Schema.PrimitiveSchema(_, _, Primitive.PByte), b) => Right(b.to)
      case _ => Left(s"not a streamed blob: ${schema.shapeId}")
    }

  private def stringMember(node: ObjectNode, name: String): Option[String] =
    node.getStringMember(name).toScala.map(_.getValue)

  private def httpRequest(
    method: String,
    uri: String,
    queryParams: List[String],
    headers: Map[String, String],
    body: Array[Byte],
  ): Request[IO] = {
    val query =
      if (queryParams.isEmpty)
        ""
      else
        queryParams.mkString("?", "&", "")
    Request[IO](
      Method.fromString(method).getOrElse(Method.POST),
      Uri.unsafeFromString(uri + query),
      headers = Headers(headers.toList.map((k, v) => Header.Raw(CIString(k), v))),
    ).withBodyStream(Stream.emits(body.toSeq))
  }

}
