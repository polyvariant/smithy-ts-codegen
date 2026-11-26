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

import alloy.SimpleRestJsonTrait
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.Ref
import cats.effect.Resource
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.http4s.HttpRoutes
import org.http4s.Method
import org.http4s.Request
import org.http4s.Response
import org.http4s.Uri
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import smithy4s.Document
import smithy4s.Endpoint
import smithy4s.Hints
import smithy4s.Service
import smithy4s.ShapeId as S4ShapeId
import smithy4s.compliancetests.internals.CanonicalSmithyDecoder
import smithy4s.compliancetests.internals.eq.EqSchemaVisitor
import smithy4s.dynamic.DynamicSchemaIndex
import smithy4s.http4s.SimpleRestJsonBuilder
import smithy4s.kinds.FunctorInterpreter
import smithy4s.kinds.Kind1
import smithy4s.schema.Schema
import software.amazon.smithy.aws.traits.protocols.RestJson1Trait
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.transform.ModelTransformer

import scala.jdk.CollectionConverters.*

/** Serves each compliance suite's services with smithy4s's own `simpleRestJson` implementation —
  * one server per suite, built dynamically from the same model the TypeScript was generated from —
  * and runs `compliance/roundtrip` against them: the generated client talking to a real smithy4s
  * server.
  *
  * The servers are driven one case at a time through `/__roundtrip`:
  *
  *   - `POST /__roundtrip/arm/<kind>/<id>[?error=<Name>]` sets up a case;
  *   - `GET /__roundtrip/result` says what the last request case saw.
  *
  * For a request case, the operation's handler compares the input smithy4s decoded with the case's
  * `params` (read the way smithy4s's own compliance suite reads them), records the outcome, and
  * fails the request on purpose. For a response case, any request is answered with the case's
  * output or error, encoded by smithy4s — as smithy4s's suite does it, through a single-endpoint
  * service that takes no input, so the answer doesn't depend on decoding the request.
  *
  * Usage: `RoundTrip <repoRoot>` runs the TypeScript side and exits with its status;
  * `RoundTrip --serve` only serves, printing the URLs, for running the tests by hand.
  */
object RoundTrip extends IOApp {

  /** What the server does with the next request. */
  private enum Armed {

    /** Compare the decoded input of `endpoint` with the case. */
    case ExpectInput(endpoint: S4ShapeId, check: Any => Either[String, Unit])

    /** Answer anything with `request` run against `routes`. */
    case Respond(routes: HttpRoutes[IO], request: Request[IO])

  }

  private enum Outcome {
    case Matched
    case Mismatch(message: String)
  }

  /** The handler fails the request after recording the input — there is no output to give. */
  private object ShortCircuit extends Exception("short-circuited on purpose", null, false, false)

  final case class SuiteCase(
    kind: String,
    id: String,
    service: ServiceShape,
    operation: S4ShapeId,
    error: Option[String],
    params: Document,
  )

  def run(args: List[String]): IO[ExitCode] = {
    val model = Model
      .assembler(getClass.getClassLoader)
      .discoverModels(getClass.getClassLoader)
      .assemble()
      .unwrap()
    // smithy4s serves simpleRestJson only: ndjsonRestJson would need smithy4s-ndjson's builder,
    // and its event-stream cases a runner of their own (see roundtrip.test.ts).
    val httpSuites = ComplianceCodegen.suites(model).filterNot(_.name == "ndjsonRestJson")
    val servers = httpSuites.traverse { suite =>
      val narrowed = ComplianceCodegen.narrow(model, suite.select(model))
      val cases = ComplianceCodegen.cases(narrowed, suite).map { c =>
        SuiteCase(
          c.kind,
          c.testCase.getId,
          c.service,
          toS4(c.operation.getId),
          c.error.map(_.getId.getName),
          toDocument(c.testCase.getParams),
        )
      }
      serve(DynamicSchemaIndex.loadModel(asSimpleRestJson(narrowed)), cases).map(suite.name -> _)
    }
    servers.use { urls =>
      args match {
        case "--serve" :: Nil =>
          urls.traverse_ { case (name, url) => IO.println(s"$name: $url") } *> IO.never
        case repoRoot :: Nil => runTypeScript(repoRoot, urls)
        case _ => IO.println("usage: RoundTrip <repoRoot> | RoundTrip --serve").as(ExitCode(2))
      }
    }
  }

  /** smithy4s serves `alloy#simpleRestJson`, so the restJson1 service is declared as one — as
    * smithy4s's own suite does with its `ProtocolTransformer`.
    */
  private def asSimpleRestJson(model: Model): Model =
    ModelTransformer
      .create()
      .replaceShapes(
        model,
        model
          .getServiceShapes
          .asScala
          .filter(_.hasTrait(classOf[RestJson1Trait]))
          .map(
            _.toBuilder.removeTrait(RestJson1Trait.ID).addTrait(new SimpleRestJsonTrait()).build()
          )
          .toList
          .asJava,
      )

  private def runTypeScript(repoRoot: String, urls: List[(String, Uri)]): IO[ExitCode] =
    IO.blocking {
      val pb =
        new ProcessBuilder(
          "pnpm",
          "--filter",
          "smithy-ts-codegen-compliance",
          "run",
          "roundtrip",
        )
          .directory(new java.io.File(repoRoot))
          .inheritIO()
      urls.foreach { case (name, url) =>
        val _ = pb.environment().put(s"ROUNDTRIP_${name.toUpperCase}_URL", url.renderString)
      }
      ExitCode(pb.start().waitFor())
    }

  private def serve(dsi: DynamicSchemaIndex, cases: List[SuiteCase]): Resource[IO, Uri] =
    for {
      armed <- Resource.eval(Ref[IO].of(Option.empty[Armed]))
      outcome <- Resource.eval(Ref[IO].of(Option.empty[Outcome]))
      services <- dsi.allServices.toList.traverse { wrapper =>
        SimpleRestJsonBuilder(wrapper.service)
          .routes(handlers(wrapper.service, armed, outcome))
          .resource
      }
      app = control(dsi, cases, armed, outcome) <+> dispatch(armed, services.reduce(_ <+> _))
      server <-
        EmberServerBuilder
          .default[IO]
          .withHost(host"127.0.0.1")
          .withPort(port"0")
          .withHttpApp(app.orNotFound)
          .withShutdownTimeout(scala.concurrent.duration.Duration.Zero)
          .build
    } yield Uri.unsafeFromString(s"http://127.0.0.1:${server.address.getPort}")

  /** Every operation of `service`, answering per the armed request case. */
  private def handlers[Alg[_[_, _, _, _, _]]](
    service: Service[Alg],
    armed: Ref[IO, Option[Armed]],
    outcome: Ref[IO, Option[Outcome]],
  ) = service.fromPolyFunction[Kind1[IO]#toKind5](new service.FunctorInterpreter[IO] {

    def apply[I, E, O, SI, SO](op: service.Operation[I, E, O, SI, SO]): IO[O] = {
      val endpoint = service.endpoint(op)
      val input = service.input(op)
      armed.get.flatMap {
        case Some(Armed.ExpectInput(expected, check)) =>
          val result =
            if (expected == endpoint.id)
              check(input)
            else
              Left(s"smithy4s routed the request to ${endpoint.id}, expected $expected")
          outcome.set(Some(result.fold(Outcome.Mismatch(_), _ => Outcome.Matched))) *>
            IO.raiseError(ShortCircuit)
        case _ => IO.raiseError(new IllegalStateException("no request case is armed"))
      }
    }

  })

  /** While a response case is armed, every request gets that case's response. */
  private def dispatch(armed: Ref[IO, Option[Armed]], services: HttpRoutes[IO]): HttpRoutes[IO] =
    HttpRoutes[IO] { req =>
      cats.data.OptionT.liftF(armed.get).flatMap {
        case Some(Armed.Respond(routes, synthetic)) => routes(synthetic)
        case _                                      => services(req)
      }
    }

  private def control(
    dsi: DynamicSchemaIndex,
    cases: List[SuiteCase],
    armed: Ref[IO, Option[Armed]],
    outcome: Ref[IO, Option[Outcome]],
  ): HttpRoutes[IO] = {
    object ErrorParam extends OptionalQueryParamDecoderMatcher[String]("error")
    HttpRoutes.of[IO] {
      case POST -> Root / "__roundtrip" / "arm" / kind / id :? ErrorParam(error) =>
        cases.find(c => c.kind == kind && c.id == id && c.error == error) match {
          case None    => NotFound(s"no $kind case $id${error.fold("")(e => s" for $e")}")
          case Some(c) =>
            arm(dsi, c).flatMap {
              case Left(message) => BadRequest(message)
              case Right(next)   => armed.set(Some(next)) *> outcome.set(None) *> NoContent()
            }
        }
      case GET -> Root / "__roundtrip" / "result" =>
        outcome.get.flatMap { o =>
          val json =
            o match {
              case Some(Outcome.Matched)       => """{"status":"ok"}"""
              case Some(Outcome.Mismatch(msg)) =>
                s"""{"status":"mismatch","message":${Node.printJson(Node.from(msg))}}"""
              case None => """{"status":"not-invoked"}"""
            }
          Ok(json)
        }
    }
  }

  private def arm(dsi: DynamicSchemaIndex, c: SuiteCase): IO[Either[String, Armed]] = {
    val found =
      for {
        wrapper <- dsi.getService(toS4(c.service.getId)).toRight(s"no service ${c.service.getId}")
        endpoint <- wrapper
          .service
          .endpoints
          .find(_.id == c.operation)
          .toRight(s"no operation ${c.operation}")
      } yield (wrapper.service, endpoint)
    found match {
      case Left(message)              => IO.pure(Left(message))
      case Right((service, endpoint)) =>
        if (c.kind == "request")
          IO.pure(expectInput(endpoint, c.params))
        else
          respond(service, endpoint, c.error, c.params)
    }
  }

  private def expectInput[I](
    endpoint: Endpoint[?, I, ?, ?, ?, ?],
    params: Document,
  ): Either[String, Armed] =
    CanonicalSmithyDecoder
      .fromSchema(endpoint.input)
      .decode(params)
      .leftMap(e => s"smithy4s could not read the case's params: ${e.getMessage}")
      .map { expected =>
        val eq = EqSchemaVisitor(endpoint.input)
        val show = Document.Encoder.fromSchema(endpoint.input)
        Armed.ExpectInput(
          endpoint.id,
          actual =>
            Either.cond(
              eq.eqv(actual.asInstanceOf[I], expected),
              (),
              s"smithy4s decoded ${show.encode(actual.asInstanceOf[I])}, expected ${show.encode(expected)}",
            ),
        )
      }

  /** A single endpoint that takes no input and answers with `endpoint`'s output or errors, mounted
    * at `GET /` — a port of `prepareService` in smithy4s's `ServerHttpComplianceTestCase`.
    */
  private final case class NoInputOp[I, E, O, SI, SO]()

  private def respond[I, E, O, SI, SO](
    original: Service[?],
    endpoint: Endpoint[?, I, E, O, SI, SO],
    error: Option[String],
    params: Document,
  ): IO[Either[String, Armed]] = {
    val result: Either[String, IO[O]] =
      error match {
        case None =>
          CanonicalSmithyDecoder
            .fromSchema(endpoint.output)
            .decode(params)
            .leftMap(e => s"smithy4s could not read the case's params: ${e.getMessage}")
            .map(IO.pure)
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
    result.traverse { answer =>
      val code = endpoint.hints.get(smithy.api.Http).map(_.code).getOrElse(200)
      val http = smithy
        .api
        .Http(
          method = smithy.api.NonEmptyString("GET"),
          uri = smithy.api.NonEmptyString("/"),
          code = code,
        )
      val operation = Schema
        .operation(S4ShapeId("custom", "endpoint"))
        .withHints(Hints(http))
        .withOutput(endpoint.output)
        .withErrorOption(endpoint.error)
      val amendedEndpoint =
        Endpoint[NoInputOp, Unit, E, O, Nothing, Nothing](operation, (_: Unit) => NoInputOp())
      val amended =
        new Service.Reflective[NoInputOp] {
          def id: S4ShapeId = S4ShapeId("custom", "service")
          // Inside a service, `Endpoint` is the service's own (operation-bound) endpoint type.
          def endpoints: Vector[Endpoint[?, ?, ?, ?, ?]] = Vector(amendedEndpoint)
          def input[I_, E_, O_, SI_, SO_](op: NoInputOp[I_, E_, O_, SI_, SO_]): I_ =
            ().asInstanceOf[I_]
          def ordinal[I_, E_, O_, SI_, SO_](op: NoInputOp[I_, E_, O_, SI_, SO_]): Int = 0
          def version: String = original.version
          def hints: Hints = original.hints
        }
      val impl =
        new FunctorInterpreter[NoInputOp, IO] {
          def apply[I_, E_, O_, SI_, SO_](op: NoInputOp[I_, E_, O_, SI_, SO_]): IO[O_] =
            answer.map(_.asInstanceOf[O_])
        }
      SimpleRestJsonBuilder(amended)
        .routes(impl)
        .make
        .leftMap(e => new IllegalStateException(e.getMessage): Throwable)
        .liftTo[IO]
        .map(routes => Armed.Respond(routes, Request[IO](Method.GET, Uri.unsafeFromString("/"))))
    }
  }

  private def toS4(id: software.amazon.smithy.model.shapes.ShapeId): S4ShapeId =
    S4ShapeId(id.getNamespace, id.getName)

  private def toDocument(node: Node): Document =
    node match {
      case o: software.amazon.smithy.model.node.ObjectNode =>
        Document.obj(o.getMembers.asScala.toSeq.map { case (k, v) => k.getValue -> toDocument(v) }*)
      case a: software.amazon.smithy.model.node.ArrayNode =>
        Document.array(a.getElements.asScala.toSeq.map(toDocument)*)
      case s: software.amazon.smithy.model.node.StringNode => Document.fromString(s.getValue)
      case n: software.amazon.smithy.model.node.NumberNode =>
        Document.fromBigDecimal(BigDecimal(n.getValue.toString))
      case b: software.amazon.smithy.model.node.BooleanNode => Document.fromBoolean(b.getValue)
      case _                                                => Document.nullDoc
    }

}
