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

import alloy.DiscriminatedUnionTrait
import alloy.JsonUnknownTrait
import alloy.NullableTrait
import alloy.OpenEnumTrait
import alloy.UuidFormatTrait
import org.polyvariant.ndjson.ServerSentEventsTrait
import org.polyvariant.smithy.ts.api.PathSegment
import org.polyvariant.smithy.ts.api.TsCodegenExtension
import software.amazon.smithy.build.PluginContext
import software.amazon.smithy.build.SmithyBuildPlugin
import software.amazon.smithy.codegen.core.ImportContainer
import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.codegen.core.SymbolProvider
import software.amazon.smithy.codegen.core.SymbolWriter
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.*
import software.amazon.smithy.model.traits.DocumentationTrait
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.model.traits.HttpErrorTrait
import software.amazon.smithy.model.traits.HttpHeaderTrait
import software.amazon.smithy.model.traits.HttpLabelTrait
import software.amazon.smithy.model.traits.HttpPayloadTrait
import software.amazon.smithy.model.traits.HttpPrefixHeadersTrait
import software.amazon.smithy.model.traits.HttpQueryParamsTrait
import software.amazon.smithy.model.traits.HttpQueryTrait
import software.amazon.smithy.model.traits.HttpResponseCodeTrait
import software.amazon.smithy.model.traits.HttpTrait
import software.amazon.smithy.model.traits.LengthTrait
import software.amazon.smithy.model.traits.MixinTrait
import software.amazon.smithy.model.traits.RequiredTrait
import software.amazon.smithy.model.traits.SparseTrait
import software.amazon.smithy.model.traits.MediaTypeTrait
import software.amazon.smithy.model.traits.PatternTrait
import software.amazon.smithy.model.traits.RangeTrait
import software.amazon.smithy.model.traits.StreamingTrait
import software.amazon.smithy.model.traits.TimestampFormatTrait
import software.amazon.smithy.model.traits.TraitDefinition
import software.amazon.smithy.utils.DependencyGraph

import java.nio.file.Paths
import java.util.function.BiFunction
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** smithy-build plugin that emits a single `generated.ts` containing:
  *   1. zod schemas + types for every reachable shape in the model;
  *   2. one `XxxError extends Error` class per `@error` shape;
  *   3. a small `Transport` interface, plus a `StreamTransport` when some operation streams;
  *   4. one client class per service, with one method per operation. The method walks the
  *      operation's `@http` trait to build the request and parses the response with the generated
  *      output schema. An operation with a `@streaming` member (per
  *      `org.polyvariant.ndjson#ndjsonRestJson`) streams that member instead, as an `AsyncIterable`
  *      — `Uint8Array` chunks for a blob, schema-checked elements for a union.
  *
  * See the package-level README for plugin settings and conventions.
  */
class TsCodegenPlugin extends SmithyBuildPlugin {

  override def getName: String = "ts-codegen"

  override def execute(ctx: PluginContext): Unit = {
    val settings = ctx.getSettings
    val outFile = Option(settings.getStringMember("outFile").orElse(null))
      .map(_.getValue)
      .getOrElse("generated.ts")
    val excludeServices = Option(settings.getArrayMember("excludeServices").orElse(null))
      .map(_.getElementsAs(classOf[software.amazon.smithy.model.node.StringNode]))
      .map(_.asScala.iterator.map(_.getValue).toSet)
      .getOrElse(Set.empty[String])
    val rendered = TsCodegenPlugin.generate(ctx.getModel, excludeServices)
    val _ = ctx.getFileManifest.writeFile(Paths.get(outFile), rendered)
  }

}

object TsCodegenPlugin {

  /** `org.polyvariant.smithy.ts#lossless`, defined as a plain smithy model in the
    * `smithy-ts-codegen-traits` artifact. There is no generated Java class for it, so it is looked
    * up by id.
    */
  private[ts] val LosslessTraitId = ShapeId.from("org.polyvariant.smithy.ts#lossless")

  /** `smithy.api#Unit`. Special only as an operation input/output, where it means "no body"; as a
    * member target it is an ordinary empty structure.
    */
  private[ts] val UnitId = ShapeId.from("smithy.api#Unit")

  /** The output is a single self-contained file, so there is nothing to import: every symbol is
    * declared in the same scope it is referenced from. The one thing that scope cannot do is hold
    * two declarations of the same name, and that is handled before a symbol ever gets here — see
    * [[TsSymbolProvider]].
    */
  private[ts] final class TsImports extends ImportContainer {
    override def importSymbol(symbol: Symbol, alias: String): Unit = ()
  }

  /** SymbolWriter with TS placeholders: $S — JS-quoted single-quoted string (escaping)
    *
    * `block(open, close)(body)` wraps `openBlock` so Scala 3 doesn't resolve the underlying Java
    * varargs overload and treat `close` as a format arg.
    */
  private[ts] final class TsWriter(
    symbols: SymbolProvider,
    /** Member references that close a cycle, and so are read lazily — see [[lazyRefs]]. */
    lazyRefs: Set[(ShapeId, ShapeId)] = Set.empty,
  ) extends SymbolWriter[TsWriter, TsImports](new TsImports) {

    /** Whether `from`'s member targeting `to` has to be a getter, because `to` is declared later.
      */
    def isLazy(from: ShapeId, to: ShapeId): Boolean = lazyRefs.contains(from -> to)

    /** The TS name of a shape, unique across the file. Almost every name in the output is a *part*
      * of an identifier rather than the whole of one — `XSchema`, `XError`, `brand<'X'>` — which is
      * why this returns a String to interpolate rather than a Symbol to format with `$T`.
      */
    def tsName(shape: Shape): String = symbols.toSymbol(shape).getName

    putFormatter('S', StringFormatter)
    // `$T` is registered by SymbolWriter itself, but its default formatter renders a symbol as
    // either `Symbol.relativize(ns)` or `Symbol.toString`, and both spell a symbol from another
    // namespace as `namespace#Name` — not a TS identifier. Since a single-file output has exactly
    // one scope and `TsSymbolProvider` has already made every name unique within it, the correct
    // rendering here is simply the name. Overridden rather than left alone so that formatting a
    // Symbol can never silently emit `ns#Name` into the output.
    putFormatter(
      'T',
      new BiFunction[Object, String, String] {

        def apply(value: Object, indent: String): String = {
          val _ = indent
          value match {
            case sym: Symbol =>
              val _ = addUseImports(sym)
              sym.getName
            case other => sys.error(s"$$T expects a Symbol, got: $other")
          }
        }

      },
    )
    trimTrailingSpaces()
    setIndentText("  ")

    def line(content: String, args: Object*): Unit = {
      val _ = write(content, args*)
    }

    /** Emit `content` literally, bypassing the `$`-expression parser. Model text (doc comments,
      * etc.) may contain a `$` (e.g. a shape member id like `City$name`); routing it through the
      * built-in `$L` formatter as an argument keeps the writer from trying to parse it as an
      * expression.
      */
    def lit(content: String): Unit = {
      val _ = write("$L", content)
    }

    def block(open: String, close: String, args: Object*)(body: => Unit): Unit = {
      val rendered = format(open, args*)
      val _ = openBlock(rendered, close, (() => body): Runnable)
    }

    /** Emits a self-contained `if (<cond>) { <body> }`, closed with its own `}`. A following
      * [[elseBlock]] / [[elseIfBlock]] is emitted independently on the next line (no `} else {`
      * cuddling).
      */
    def ifBlock(cond: String, args: Object*)(body: => Unit): Unit =
      block(s"if ($cond) {", "}", args*)(body)

    /** Emits a standalone `else { <body> }`, assuming an `if`/`else if` was just closed above it.
      */
    def elseBlock(body: => Unit): Unit =
      block("else {", "}")(body)

    /** Emits a standalone `else if (<cond>) { <body> }`, assuming an `if` was just closed above it.
      */
    def elseIfBlock(cond: String, args: Object*)(body: => Unit): Unit =
      block(s"else if ($cond) {", "}", args*)(body)

    /** Emits `try { <tryBody> } catch (<binding>) { <catchBody> }` with the `try`/`catch` kept
      * contiguous (the intervening `} catch (...) {` shares a line, as JS requires).
      */
    def tryCatchBlock(binding: String)(tryBody: => Unit)(catchBody: => Unit): Unit = {
      block("try {", s"} catch ($binding) {")(tryBody)
      // The line above already opened the `catch` block, so just emit its body
      // and the final closing brace at the right indent.
      indent()
      catchBody
      dedent()
      line("}")
    }

  }

  /** Names every emitted shape, uniquely, within the single scope of the output file.
    *
    * Smithy shape ids are unique but TS declarations are named by the unqualified part, so two
    * shapes from different namespaces — `com.example.a#ProfileId` and `com.example.b#ProfileId` —
    * would both want to be `ProfileId`. A model that aggregates many namespaces hits this
    * immediately, while a single-namespace model never can.
    *
    * The scheme: a name owned by exactly one shape is used bare, which is the overwhelmingly common
    * case; when several shapes want the same name, *every* one of them is qualified with its
    * namespace. Qualifying all of them rather than picking a winner keeps a name's meaning stable —
    * if one kept the bare name, adding a new namespace that happens to reuse it would silently
    * change what the bare name refers to and quietly break downstream code that imported it.
    *
    * Namespace dots become underscores, since a TS identifier cannot contain a dot.
    *
    * A shape's name is more than one identifier, though: `X` is a type, and `XSchema`, `encodeX`
    * and `decodeX` are values, plus `XError` for an `@error` shape and `decodeXResponse` for an
    * operation's output or error. A shape is qualified the same way when one of those is already
    * taken by something that isn't a shape: a global the output itself uses (a shape `Record` would
    * shadow `Record<K, V>`), or one of the generator's own declarations (a shape `Stream` would
    * declare `decodeStream`).
    *
    * Two shapes can also want one identifier between them without sharing a name — an `@error`
    * `Foo` declares `FooError`, which a shape `FooError` declares too. Qualifying can't separate
    * those when they share a namespace, so that is an error naming both, rather than a file that
    * doesn't compile. The names a service gets (`XClient`, `XMock`, `XHandlers`) count too. A type
    * and a value may share a name in TS, so `FooSchema` the type beside `FooSchema` the value of
    * `Foo` is no clash.
    *
    * @param responses
    *   the shapes that get a `decodeXResponse`: operations' outputs and errors
    * @param services
    *   the names of the services emitted, which claim `XClient`, `XMock` and `XHandlers`
    */
  private[ts] final class TsSymbolProvider(
    shapes: List[Shape],
    responses: Set[ShapeId] = Set.empty,
    services: List[String] = Nil,
  ) extends SymbolProvider {

    private val namesById: Map[ShapeId, String] = {
      def claims(shape: Shape, name: String): List[Claim] =
        List(Claim.tpe(name), Claim.value(s"${name}Schema"), Claim.value(s"encode$name"))
          ++ List(Claim.value(s"decode$name"))
          ++ Option
            .when(shape.hasTrait(classOf[ErrorTrait]))(s"${name}Error")
            .toList
            .flatMap(n => List(Claim.tpe(n), Claim.value(n)))
          ++ Option.when(responses.contains(shape.getId))(Claim.value(s"decode${name}Response"))
      val sharedNames = shapes.groupBy(_.getId.getName).filter(_._2.sizeIs > 1).keySet
      val named = shapes.map { shape =>
        val id = shape.getId
        val qualify =
          sharedNames(id.getName) || claims(shape, id.getName).exists(Claim.taken)
        val name =
          if (qualify)
            s"${id.getNamespace.replace('.', '_')}_${id.getName}"
          else
            id.getName
        (shape, name)
      }
      // What every declaration ends up claiming, by whom: a shape or a service.
      val owners: List[(Claim, String)] =
        named.flatMap((shape, name) =>
          claims(shape, name).distinct.map(_ -> shape.getId.toString)
        ) ++
          services.flatMap { s =>
            List(Claim.tpe(s"${s}Client"), Claim.value(s"${s}Client"), Claim.value(s"${s}Mock"))
              .map(_ -> s"service $s") ++ List(Claim.tpe(s"${s}Handlers") -> s"service $s")
          }
      owners.groupMap(_._1)(_._2).find(_._2.sizeIs > 1).foreach { (claim, who) =>
        sys.error(
          s"${who.sorted.mkString(" and ")} would ${if (who.sizeIs == 2)
              "both"
            else
              "all"} declare " +
            s"`${claim.name}` in the generated TypeScript; rename one of them"
        )
      }
      named.map((shape, name) => shape.getId -> name).toMap
    }

    def toSymbol(shape: Shape): Symbol = {
      val id = shape.getId
      val name = namesById.getOrElse(
        id,
        sys.error(s"no TS name for $id — it was not among the shapes this provider was built from"),
      )
      Symbol
        .builder()
        .name(name)
        // The Smithy namespace, kept for provenance in errors and in `getFullName`. It is
        // deliberately *not* used to render references: see the `$T` formatter in `TsWriter`.
        .namespace(id.getNamespace, ".")
        .putProperty("shapeId", id)
        .build()
    }

  }

  /** An identifier a shape's declarations want, in TS's type or value declaration space. */
  private final case class Claim(name: String, isType: Boolean)

  private object Claim {

    def tpe(name: String): Claim = Claim(name, isType = true)
    def value(name: String): Claim = Claim(name, isType = false)

    /** Globals the output refers to, each in the declaration space it uses it in. A shape's own
      * declaration of the same name would shadow it in the generated code itself; in the other
      * space it is harmless, so a shape named `Set` keeps its name — the output only ever says
      * `new Set(...)`, a value, while the shape declares a type.
      */
    private val globalTypes = Set(
      "AsyncGenerator",
      "AsyncIterable",
      "Date",
      "Error",
      "Partial",
      "Promise",
      "Record",
      "Uint8Array",
    )

    private val globalValues = Set(
      "Array",
      "BigInt",
      "Date",
      "Error",
      "JSON",
      "Number",
      "Object",
      "RegExp",
      "String",
    )

    /** What the generator declares for itself: the transport and mock types, the error classes and
      * the wire helpers. A class is both a type and a value, so these are taken in both spaces.
      */
    private val generated = Set(
      "BoundMockService",
      "MissingStreamError",
      "MockHttpError",
      "MockMatch",
      "MockOperation",
      "MockRequest",
      "MockResponse",
      "MockServiceDescriptor",
      "MockUriSegment",
      "StreamDecodeError",
      "StreamEncoding",
      "StreamTransport",
      "StreamTransportRequest",
      "StreamTransportResponse",
      "Transport",
      "TransportOptions",
      "TransportRequest",
      "TransportResponse",
      "TypedMockError",
      "UnauthenticatedError",
      "UnexpectedResponseError",
      "decodeStream",
      "emptyStream",
      "isTypedMockError",
      "matchSegments",
      "mockService",
      "mockServices",
    )

    /** Whether something other than a shape has `claim`'s name. */
    def taken(claim: Claim): Boolean =
      // `import { z } from 'zod'` binds the name in both spaces.
      claim.name == "z" || generated(claim.name) ||
        (if (claim.isType)
           globalTypes(claim.name)
         else
           globalValues(claim.name) || claim.name.startsWith("wire"))

  }

  private object StringFormatter extends BiFunction[Object, String, String] {

    def apply(value: Object, indent: String): String = {
      val _ = indent
      val s = value.toString
      "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
    }

  }

  /** Loads the `TsCodegenExtension` implementations visible on the current classpath via
    * `ServiceLoader`. This is what [[generate]] uses when not given an explicit list.
    */
  def loadExtensions(): List[TsCodegenExtension] =
    java
      .util
      .ServiceLoader
      .load(classOf[TsCodegenExtension])
      .iterator
      .asScala
      .toList

  def generate(
    model: Model,
    excludeServices: Set[String],
    extensions: List[TsCodegenExtension] = loadExtensions(),
  ): String = {
    val paths = new PathResolver(extensions)
    // `model.shapes` iterates a hash map keyed by ShapeId, so its order varies
    // between environments (JVM, classpath, filesystem). Sort by shape id to give
    // the topological sort below a deterministic starting order — otherwise the
    // emitted file differs machine-to-machine and codegen-drift checks fail.
    val emittables =
      model.shapes.iterator.asScala.filter(emittable).toList.sortBy(_.getId.toString)
    val ordered = topoSort(emittables)
    // The provider is built from everything that will be emitted, so it can see the whole set of
    // wanted names at once and decide which of them collide.
    val servicesToEmit = model
      .getServiceShapes
      .asScala
      .toList
      .filterNot(s => excludeServices.contains(s.getId.toString))
      .sortBy(_.getId.toString)
    // Every operation's output and errors: a superset of the shapes that get a response decoder
    // (a streamed output gets none), which only matters to a name that would clash.
    val responses =
      model
        .getOperationShapes
        .asScala
        .flatMap(op => op.getOutputShape :: op.getErrors.asScala.toList)
        .toSet ++ servicesToEmit.flatMap(_.getErrors.asScala)
    val w =
      new TsWriter(
        new TsSymbolProvider(ordered, responses, servicesToEmit.map(_.getId.getName)),
        lazyRefs(emittables),
      )

    w.line("// Generated by smithy-ts-codegen — do not edit.")
    w.line("")
    w.line("import { z } from 'zod'")
    w.line("")
    w.line("// --- Data types ---")
    w.line("")
    ordered.foreach(shape => writeShape(w, model, shape))

    val errorShapes = ordered.collect {
      case s: StructureShape if s.hasTrait(classOf[ErrorTrait]) => s
    }
    if (errorShapes.nonEmpty) {
      w.line("")
      w.line("// --- Error classes ---")
      w.line("")
      errorShapes.foreach(writeErrorClass(w, _))
    }

    w.line("")
    w.line("// --- Wire codecs ---")
    w.line("")
    WireCodec.writeHelpers(w)
    ordered.foreach(WireCodec.writeCodec(w, model, _))

    if (servicesToEmit.nonEmpty) {
      w.line("")
      w.line("// --- Transport ---")
      w.line("")
      writeTransport(w)
      // The streaming half of the transport is only emitted when some operation
      // actually streams, so models without streaming are unaffected.
      if (servicesToEmit.exists(hasStreamingOperation(w, model, _)))
        writeStreamTransport(w)
      w.line("")
      w.line("// --- Response decoding ---")
      w.line("")
      responseShapes(w, model, servicesToEmit).foreach(WireCodec.writeResponseDecoder(w, model, _))
      w.line("")
      w.line("// --- Service clients ---")
      w.line("")
      servicesToEmit.foreach(svc => writeClient(w, model, svc, paths))
      w.line("")
      w.line("// --- Storybook mock server ---")
      w.line("")
      writeMockRuntime(w, servicesToEmit.exists(hasStreamingOperation(w, model, _)))
      servicesToEmit.foreach(svc => writeMockService(w, model, svc, paths))
    }

    w.toString
  }

  /** Every structure a client reads from a whole response: unary outputs and declared errors. */
  private def responseShapes(
    w: TsWriter,
    model: Model,
    services: List[ServiceShape],
  ): List[StructureShape] =
    services
      .flatMap { svc =>
        serviceOperations(model, svc).flatMap { op =>
          val output = model.expectShape(op.getOutputShape, classOf[StructureShape])
          val unaryOutput =
            Option.when(output.getId != UnitId && opStreams(w, model, op).output.isEmpty)(output)
          unaryOutput.toList ++
            op.getErrors(svc).asScala.toList.map(model.expectShape(_, classOf[StructureShape]))
        }
      }
      .distinctBy(_.getId)
      .sortBy(_.getId.toString)

  // --------------------------------------------------------------------------
  // Shape selection & ordering
  // --------------------------------------------------------------------------

  /** Member references that close a cycle: `from`'s member targets `to`, and `to` leads back to
    * `from`. Such a reference cannot be resolved when the schema is declared, so it is emitted as a
    * getter (`get m() { return ToSchema }`), which zod reads lazily and which TypeScript infers
    * through; [[topoSort]] then ignores the edge, which leaves the graph acyclic.
    *
    * Only structures and tagged unions are deferred, since only their members are getters: a list
    * or map is an expression over its element schema, and a discriminated union extends its arms'
    * schemas, so those references stay eager. Smithy requires every cycle to pass through a
    * structure or union, and a discriminated union's arms are structures, so every cycle keeps a
    * deferred edge.
    */
  private[ts] def lazyRefs(shapes: List[Shape]): Set[(ShapeId, ShapeId)] = {
    val byId = shapes.map(s => s.getId -> s).toMap
    def targets(id: ShapeId): List[ShapeId] =
      byId
        .get(id)
        .toList
        .flatMap(_.getAllMembers.asScala.values.map(_.getTarget))
        .filter(byId.contains)
    @annotation.tailrec
    def reaches(frontier: List[ShapeId], seen: Set[ShapeId], goal: ShapeId): Boolean =
      frontier match {
        case Nil                       => false
        case head :: _ if head == goal => true
        case head :: tail              =>
          val next = targets(head).filterNot(seen.contains)
          reaches(next ++ tail, seen ++ next, goal)
      }
    shapes
      .filter {
        case _: StructureShape => true
        case u: UnionShape     => !u.hasTrait(classOf[DiscriminatedUnionTrait])
        case _                 => false
      }
      .flatMap { from =>
        targets(from.getId)
          .filter(to => reaches(List(to), Set(to), from.getId))
          .map(from.getId -> _)
      }
      .toSet
  }

  /** Orders shapes so every shape follows the ones it references. `DependencyGraph` is backed by
    * `LinkedHashMap`/`LinkedHashSet`, so it preserves insertion order for otherwise-unordered
    * nodes: feeding it a list sorted by shape id (and sorted dependency sets) makes the result
    * byte-stable across environments.
    */
  private[ts] def topoSort(shapes: List[Shape]): List[Shape] = {
    val emittableSet = shapes.map(_.getId).toSet
    val deferred = lazyRefs(shapes)
    val graph = new DependencyGraph[ShapeId]()
    shapes.foreach { shape =>
      graph.add(shape.getId)
      val deps =
        shape
          .getAllMembers
          .asScala
          .values
          .iterator
          .map(_.getTarget)
          .filter(emittableSet.contains)
          .filterNot(_ == shape.getId)
          .filterNot(to => deferred.contains(shape.getId -> to))
          .toList
          .distinct
          .sortBy(_.toString)
      graph.addDependencies(shape.getId, new java.util.LinkedHashSet(deps.asJava))
    }
    // Cannot happen — see `lazyRefs` — but an unsorted output would only fail at runtime, so
    // fail here, naming the cycle, if it ever does.
    val cycles = graph.findCycles.asScala.toList
    if (cycles.nonEmpty) {
      val rendered = cycles.map(_.asScala.map(_.toString).mkString(" -> ")).mkString("; ")
      sys.error(s"ts-codegen could not order recursive shapes. Cycles: $rendered")
    }
    val byId = shapes.map(s => s.getId -> s).toMap
    graph.toSortedList.asScala.iterator.flatMap(byId.get).toList
  }

  private def emittable(shape: Shape): Boolean = {
    val id = shape.getId
    // Prelude shapes are the primitives, spelled inline by `primitiveSchema` — with the
    // exception of `Unit`, which is a structure like any other and is emitted as one. It
    // only means "nothing" as an operation input/output; as a member target it is a real
    // (empty) value, which is how a valueless union variant is spelled in Smithy.
    if (id.getNamespace == "smithy.api")
      id == UnitId
    else if (id.getNamespace.startsWith("smithy4s"))
      false
    else if (shape.hasTrait(classOf[MixinTrait]))
      false
    else if (shape.hasTrait(classOf[TraitDefinition]))
      false
    else
      shape match {
        case _: ServiceShape | _: OperationShape | _: ResourceShape | _: MemberShape => false
        case _                                                                       => true
      }
  }

  // --------------------------------------------------------------------------
  // Data shapes
  // --------------------------------------------------------------------------

  private def writeShape(w: TsWriter, model: Model, shape: Shape): Unit = {
    // A `@streaming blob` is never a value — it only ever appears as the body of
    // a streaming operation, where the client/mock surface it as an
    // `AsyncIterable<Uint8Array>`. Emitting the usual branded-string alias for
    // it would advertise a type nothing can legitimately produce, so emit the
    // stream type instead.
    if (shape.isInstanceOf[BlobShape] && shape.hasTrait(classOf[StreamingTrait])) {
      writeDoc(w, shape)
      writeStreamingBlobAlias(w, shape)
      w.line("")
      return
    }
    writeDoc(w, shape)
    shape match {
      case _: StructureShape => writeStructure(w, model, shape.asStructureShape().get())
      case _: UnionShape     => writeUnion(w, model, shape.asUnionShape().get())
      case _: EnumShape      => writeEnum(w, shape.asEnumShape().get())
      case _: ListShape      => writeList(w, model, shape.asListShape().get())
      case _: MapShape       => writeMap(w, model, shape.asMapShape().get())
      case _                 => writeAlias(w, shape)
    }
    w.line("")
  }

  private def writeDoc(w: TsWriter, shape: Shape): Unit =
    shape.getTrait(classOf[DocumentationTrait]).toScala.foreach { t =>
      w.line("/**")
      t.getValue.split("\n").foreach(line => w.lit(s" * $line"))
      w.line(" */")
    }

  private def writeStructure(w: TsWriter, model: Model, shape: StructureShape): Unit = {
    val name = w.tsName(shape)
    // A `@streaming` member is the body of a stream, not a value in an object:
    // it has no schema to sit in this one (a streamed blob has no schema at
    // all), and validating it would mean consuming the stream. It is left out
    // here and re-attached, as an `AsyncIterable`, by the client and the mock.
    val streamed = streamInfo(w, model, shape).map(_.memberName)
    w.block(s"export const ${name}Schema = z.object({", "})") {
      shape.getAllMembers.asScala.toList.filterNot { case (n, _) => streamed.contains(n) }.foreach {
        case (memberName, member) =>
          member.getTrait(classOf[DocumentationTrait]).toScala.foreach { t =>
            w.lit(s"/** ${t.getValue.replace("\n", " ").trim} */")
          }
          val target = model.expectShape(member.getTarget)
          val schemaExpr = inlineSchemaExpr(w, target, member)
          val required = member.hasTrait(classOf[RequiredTrait])
          val nullable = member.hasTrait(classOf[NullableTrait])
          val finalExpr =
            if (nullable)
              schemaExpr + ".nullish()"
            else if (required)
              schemaExpr
            else
              schemaExpr + ".optional()"
          if (w.isLazy(shape.getId, member.getTarget))
            w.line(s"get $memberName() { return $finalExpr },")
          else
            w.line(s"$memberName: $finalExpr,")
      }
    }
    // The streamed member is absent from the schema, so the inferred type would
    // lack it too. Declare the type explicitly so `Omit<X, 'member'>` in the
    // client and the handlers still refers to something with that member.
    streamed match {
      case None             => w.line(s"export type $name = z.infer<typeof ${name}Schema>")
      case Some(memberName) =>
        val info = streamInfo(w, model, shape).get
        val streamMember = s"{ $memberName: ${info.streamType} }"
        // `z.infer` of an empty `z.object({})` is `Record<string, never>`, which
        // would make every property of the intersection `never`. When the stream
        // is the only member there is nothing to intersect with, so emit it alone.
        val remaining = shape.getAllMembers.asScala.keySet.filterNot(_ == memberName)
        if (remaining.isEmpty)
          w.line(s"export type $name = $streamMember")
        else
          w.line(s"export type $name = z.infer<typeof ${name}Schema> & $streamMember")
    }
  }

  /** A union member tagged `alloy#jsonUnknown` makes the union *open*: on the wire, any
    * discriminator the model does not know about activates that member, carrying the whole object.
    * So it is not a variant of its own — emitting `{ theUnknownMember: ... }` would describe a key
    * that never appears — but a catch-all arm.
    *
    * Two encodings, per `alloy#discriminated`:
    *
    *   - tagged (the default): a single-key envelope, `{ "one": { "a": 123 } }`. The catch-all arm
    *     accepts any single-key object, and has to come *last* — `z.union` tries its options in
    *     order, and a permissive record placed earlier would match (and swallow) every known
    *     variant.
    *   - discriminated: the variant is flattened into the object and labelled with a discriminator
    *     property, `{ "a": 123, "type": "one" }`. A *closed* discriminated union uses
    *     `z.discriminatedUnion`, which dispatches on that property in one step and reports errors
    *     against the selected arm rather than against every arm. An *open* one cannot: zod builds
    *     its dispatch map from the arms' literal discriminator values, and rejects an arm whose
    *     discriminator is a plain `z.string()` when the schema is constructed. So the open case
    *     falls back to `z.union` and trial dispatch, catch-all last.
    */
  private def writeUnion(w: TsWriter, model: Model, shape: UnionShape): Unit = {
    val name = w.tsName(shape)
    val (unknownMembers, knownMembers) =
      shape
        .getAllMembers
        .asScala
        .toList
        .partition { case (_, m) => m.hasTrait(classOf[JsonUnknownTrait]) }
    val open = unknownMembers.nonEmpty
    val discriminator = shape.getTrait(classOf[DiscriminatedUnionTrait]).toScala.map(_.getValue)
    if (knownMembers.isEmpty && !open) {
      w.line(s"export const ${name}Schema = z.never()")
      w.line(s"export type $name = z.infer<typeof ${name}Schema>")
    } else
      discriminator match {
        case Some(field) => writeDiscriminatedUnion(w, model, shape, field, knownMembers, open)
        case None        => writeTaggedUnion(w, model, shape, knownMembers, open)
      }
  }

  private def writeTaggedUnion(
    w: TsWriter,
    model: Model,
    shape: UnionShape,
    knownMembers: List[(String, MemberShape)],
    open: Boolean,
  ): Unit = {
    val name = w.tsName(shape)
    w.block(s"export const ${name}Schema = z.union([", "])") {
      knownMembers.foreach { case (memberName, member) =>
        val target = model.expectShape(member.getTarget)
        val variant = inlineSchemaExpr(w, target, member)
        if (w.isLazy(shape.getId, member.getTarget))
          w.line(s"z.object({ get $memberName() { return $variant } }),")
        else
          w.line(s"z.object({ $memberName: $variant }),")
      }
      if (open)
        w.line("z.record(z.string(), z.unknown()),")
    }
    w.line(s"export type $name = z.infer<typeof ${name}Schema>")
  }

  /** A discriminated variant is flattened into the enclosing object, so it has to *be* an object:
    * there is nothing to spread out of a string or a list. Hence the check — `alloy`'s own selector
    * does not constrain the member targets, so an unusable model would otherwise reach codegen and
    * emit a schema that cannot match anything.
    *
    * The known arms extend the target structure's schema with the discriminator literal. The
    * catch-all arm for an open union keeps the discriminator (a `string`, since the value is
    * exactly what the model does not know) and allows anything alongside it: unlike the tagged case
    * the envelope is not the unknown part — the shape is known, only the label is not. It comes
    * last, because an open union is dispatched by trial and it would otherwise match every known
    * variant.
    */
  private def writeDiscriminatedUnion(
    w: TsWriter,
    model: Model,
    shape: UnionShape,
    field: String,
    knownMembers: List[(String, MemberShape)],
    open: Boolean,
  ): Unit = {
    val name = w.tsName(shape)
    knownMembers.foreach { case (memberName, member) =>
      val target = model.expectShape(member.getTarget)
      if (!target.isStructureShape)
        sys.error(
          s"union ${shape.getId} is @discriminated, so its members are flattened into the " +
            s"encoded object and must target structures, but member `$memberName` targets " +
            s"${target.getId} (${target.getType})"
        )
    }
    // `z.discriminatedUnion` derives its dispatch map from the arms' literal discriminators, so it
    // rejects the open union's catch-all arm outright — when the schema is constructed, not when it
    // parses. The open case therefore uses `z.union`, whose trial dispatch tolerates it.
    val opener =
      if (open)
        "z.union(["
      else
        s"z.discriminatedUnion(${jsString(field)}, ["
    w.block(s"export const ${name}Schema = $opener", "])") {
      knownMembers.foreach { case (memberName, member) =>
        val target = model.expectShape(member.getTarget)
        val variant = inlineSchemaExpr(w, target, member)
        w.line(s"$variant.extend({ ${jsString(field)}: z.literal(${jsString(memberName)}) }),")
      }
      // Last, so trial dispatch reaches the known arms first. Note the consequence: a known
      // discriminator carrying a payload that does not validate lands here rather than failing.
      // That is what an open union asks for — a client built against an older model cannot tell a
      // malformed variant from a newer one it does not know.
      if (open)
        w.line(s"z.object({ ${jsString(field)}: z.string() }).catchall(z.unknown()),")
    }
    w.line(s"export type $name = z.infer<typeof ${name}Schema>")
  }

  /** `alloy#openEnum` means the server may send a value this model does not list, so the schema has
    * to accept any string.
    *
    * The type is written out rather than inferred: `z.infer` of that union collapses to plain
    * `string`, which would lose the known values as completions. `"a" | "b" | (string & {})` keeps
    * them — the `& {}` stops TypeScript from eagerly widening the whole union to `string`.
    */
  private def writeEnum(w: TsWriter, shape: EnumShape): Unit = {
    val name = w.tsName(shape)
    val values = shape.getEnumValues.asScala.values.toList
    val literals = values.map(jsString).mkString(", ")
    if (shape.hasTrait(classOf[OpenEnumTrait])) {
      w.line(s"export const ${name}Schema = z.union([z.enum([$literals]), z.string()])")
      val union = values.map(v => "\"" + v + "\"").mkString(" | ")
      w.line(s"export type $name = $union | (string & {})")
    } else {
      w.line(s"export const ${name}Schema = z.enum([$literals])")
      w.line(s"export type $name = z.infer<typeof ${name}Schema>")
    }
  }

  private def writeList(w: TsWriter, model: Model, shape: ListShape): Unit = {
    val name = w.tsName(shape)
    val target = model.expectShape(shape.getMember.getTarget)
    val elem = inlineSchemaExpr(w, target, shape.getMember) + sparseSuffix(shape)
    w.line(s"export const ${name}Schema = z.array($elem)${constraintChecks(shape, shape)}")
    w.line(s"export type $name = z.infer<typeof ${name}Schema>")
  }

  private def writeMap(w: TsWriter, model: Model, shape: MapShape): Unit = {
    val name = w.tsName(shape)
    val value = model.expectShape(shape.getValue.getTarget)
    val valueExpr = inlineSchemaExpr(w, value, shape.getValue) + sparseSuffix(shape)
    w.line(
      s"export const ${name}Schema = z.record(z.string(), $valueExpr)${constraintChecks(shape, shape)}"
    )
    w.line(s"export type $name = z.infer<typeof ${name}Schema>")
  }

  /** A `@sparse` collection holds `null` elements as values. */
  private def sparseSuffix(shape: Shape): String =
    if (shape.hasTrait(classOf[SparseTrait]))
      ".nullable()"
    else
      ""

  private def writeAlias(w: TsWriter, shape: Shape): Unit = {
    val name = w.tsName(shape)
    val base = primitiveSchema(shape)
    val branded = s"$base${constraintChecks(shape, shape)}.brand<'$name'>()"
    w.line(s"export const ${name}Schema = $branded")
    w.line(s"export type $name = z.infer<typeof ${name}Schema>")
  }

  /** A `@streaming blob` alias: a type alias only, with no zod schema. There is nothing to validate
    * — the bytes are the body verbatim — and a schema would only invite parsing a stream as if it
    * were a value.
    */
  private def writeStreamingBlobAlias(w: TsWriter, shape: Shape): Unit = {
    val name = w.tsName(shape)
    w.line(s"export type $name = AsyncIterable<Uint8Array>")
  }

  private def inlineSchemaExpr(w: TsWriter, target: Shape): String = {
    val id = target.getId
    if (id.getNamespace == "smithy.api" && id != UnitId)
      primitiveSchema(target)
    else
      s"${w.tsName(target)}Schema"
  }

  /** `@lossless` (see the `smithy-ts-codegen-traits` model) marks a numeric member whose exact
    * value must survive, because JS numbers cannot hold every value of the underlying shape. Such a
    * member is typed `number | string`: a lossless transport hands back a `number` when the value
    * fits exactly and its decimal string when it does not, so the schema has to admit both. It is
    * member-scoped, so it can only be honored where the member is in scope — hence this overload
    * alongside the shape-only one above.
    */
  private[ts] def inlineSchemaExpr(w: TsWriter, target: Shape, member: MemberShape): String = {
    val base =
      if (isLossless(member))
        "z.union([z.number(), z.string()])"
      else
        inlineSchemaExpr(w, target)
    base + constraintChecks(member, target)
  }

  /** The constraint traits `carrier` itself holds (`@range`, `@length`, `@pattern`, and alloy's
    * `@uuidFormat`), as zod refinements on a value of `target`. A shape's own constraints are
    * checked in its schema, a member's where the member uses it, so each is checked once.
    *
    * They are `refine`s rather than zod's own `.min`/`.regex`, which exist only on some schema
    * kinds: `refine` keeps the inferred type and applies to a branded alias, a `@lossless` union or
    * a record alike. As in smithy4s, which the client follows: a string's length is its UTF-16
    * length, and a pattern matches anywhere unless it is anchored.
    */
  /** A regex as a JS string literal for `new RegExp`, with `$` spelled `\x24`: the same string at
    * runtime, but none of the writer's `$` format markers, so it can go through `w.line` like any
    * other schema expression.
    */
  private def regexSource(pattern: String): String = jsString(pattern).replace("$", "\\x24")

  private def constraintChecks(carrier: Shape, target: Shape): String = {
    val range = carrier.getTrait(classOf[RangeTrait]).toScala.toList.flatMap { t =>
      t.getMin.toScala.map(min => s".refine((v) => Number(v) >= $min, 'must be at least $min')") ++
        t.getMax.toScala.map(max => s".refine((v) => Number(v) <= $max, 'must be at most $max')")
    }
    val size =
      target match {
        case _: StringShape => "v.length"
        case _: ListShape   => "v.length"
        case _: MapShape    => "Object.keys(v).length"
        case _              => ""
      }
    val length =
      if (size.isEmpty)
        Nil
      else
        carrier.getTrait(classOf[LengthTrait]).toScala.toList.flatMap { t =>
          t.getMin
            .toScala
            .map(min => s".refine((v) => $size >= $min, 'length must be at least $min')") ++
            t.getMax
              .toScala
              .map(max => s".refine((v) => $size <= $max, 'length must be at most $max')")
        }
    val pattern = carrier.getTrait(classOf[PatternTrait]).toScala.toList.map { t =>
      val p = regexSource(t.getValue)
      s".refine((v) => new RegExp($p).test(v), 'must match ' + $p)"
    }
    val uuid =
      if (carrier.hasTrait(classOf[UuidFormatTrait])) {
        val p = regexSource(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )
        List(s".refine((v) => new RegExp($p).test(v), 'must be a UUID')")
      } else
        Nil
    (range ++ length ++ pattern ++ uuid).mkString
  }

  private def isLossless(member: MemberShape): Boolean =
    member.hasTrait(LosslessTraitId)

  private def primitiveSchema(shape: Shape): String =
    shape match {
      case _: BooleanShape    => "z.boolean()"
      case _: StringShape     => "z.string()"
      case _: ByteShape       => "z.number().int()"
      case _: ShortShape      => "z.number().int()"
      case _: IntegerShape    => "z.number().int()"
      case _: LongShape       => "z.number().int()"
      case _: FloatShape      => "z.number()"
      case _: DoubleShape     => "z.number()"
      case _: BigIntegerShape => "z.number().int()"
      case _: BigDecimalShape => "z.number()"
      case ts: TimestampShape =>
        val _ = ts.getTrait(classOf[TimestampFormatTrait])
        "z.coerce.date()"
      case _: DocumentShape => "z.unknown()"
      case _: BlobShape     => "z.string()"
      case other => sys.error(s"unsupported primitive shape: ${other.getId} (${other.getType})")
    }

  // --------------------------------------------------------------------------
  // Streaming (org.polyvariant.ndjson#ndjsonRestJson)
  // --------------------------------------------------------------------------
  //
  // Smithy restricts `@streaming` to `:is(blob, union)` and the ndjson protocol
  // requires such a member to carry `@httpPayload`, so a structure has at most
  // one streaming member and it always *is* the body. That gives three framings,
  // applied identically to input and output:
  //
  //   - `@streaming blob`  -> the body verbatim (`application/octet-stream`),
  //     surfaced as `AsyncIterable<Uint8Array>`;
  //   - `@streaming union` -> one JSON value per line (`application/x-ndjson`),
  //     surfaced as `AsyncIterable<TheUnion>`;
  //   - `@streaming union` marked `@serverSentEvents` -> one server-sent event
  //     per JSON value (`text/event-stream`), optionally closed by a terminator
  //     event, surfaced as `AsyncIterable<TheUnion>` too.
  //
  // Framing itself is the transport's job (see `StreamTransport`): it hands us
  // already-split values and takes values back. What the generated code adds on
  // top is the per-element zod schema, so a stream is as typed as a unary body.

  /** How a `@streaming` member is framed on the wire, and what the TS element type is. */
  private sealed trait StreamFraming {

    /** Value of `streamEncoding` on the transport request — what the transport must frame as. */
    def encoding: String

    /** TS type of one element of the stream. */
    def elementType: String

    /** The data of the event that closes an `sse` stream, if the union declares one. */
    def terminator: Option[String] = None

  }

  private object StreamFraming {

    /** `@streaming blob`: raw bytes, no per-element schema, labelled with the blob's `@mediaType`
      * if it has one.
      */
    final case class Binary(mediaType: Option[String]) extends StreamFraming {
      val encoding = "binary"
      val elementType = "Uint8Array"
    }

    /** A `@streaming union`: JSON values of the union, validated per element. */
    sealed trait Json extends StreamFraming {
      def shapeName: String
      def elementType: String = shapeName
      def schemaExpr: String = s"${shapeName}Schema"
    }

    /** `@streaming union`: newline-delimited JSON, one union value per line. */
    final case class Ndjson(shapeName: String) extends Json {
      val encoding = "ndjson"
    }

    /** `@streaming @serverSentEvents union`: one server-sent event per union value. */
    final case class Sse(shapeName: String, override val terminator: Option[String]) extends Json {
      val encoding = "sse"
    }

  }

  /** The `@streaming` member of a structure, if any: its member name and framing. */
  private final case class StreamInfo(memberName: String, framing: StreamFraming) {
    def elementType: String = framing.elementType

    /** TS type of the stream as a whole, as seen by user code. */
    def streamType: String = s"AsyncIterable<${framing.elementType}>"

  }

  /** Finds the `@streaming` member of `shape`, if it has one.
    *
    * The trait may sit on the member or on the targeted shape (smithy allows either), so both are
    * checked. A structure with more than one streaming member is rejected: the stream is the body,
    * and there is only one body.
    */
  private def streamInfo(w: TsWriter, model: Model, shape: StructureShape): Option[StreamInfo] = {
    val streaming = shape
      .getAllMembers
      .asScala
      .toList
      .filter { case (_, m) =>
        m.hasTrait(classOf[StreamingTrait]) ||
        model.expectShape(m.getTarget).hasTrait(classOf[StreamingTrait])
      }
    if (streaming.sizeIs > 1)
      sys.error(
        s"structure ${shape.getId} has multiple @streaming members (${streaming.map(_._1).mkString(", ")}); at most one is allowed"
      )
    streaming.headOption.map { case (memberName, member) =>
      val target = model.expectShape(member.getTarget)
      val framing =
        target match {
          case b: BlobShape =>
            StreamFraming.Binary(b.getTrait(classOf[MediaTypeTrait]).toScala.map(_.getValue))
          case u: UnionShape =>
            u.getTrait(classOf[ServerSentEventsTrait]).toScala match {
              case Some(sse) => StreamFraming.Sse(w.tsName(u), sse.getTerminator.toScala)
              case None      => StreamFraming.Ndjson(w.tsName(u))
            }
          case other =>
            sys.error(
              s"@streaming member ${shape.getId}$$$memberName targets ${other.getId} (${other.getType}); only blob and union are supported"
            )
        }
      StreamInfo(memberName, framing)
    }
  }

  /** The streaming shape of one operation: what streams in, what streams out. */
  private final case class OpStreams(input: Option[StreamInfo], output: Option[StreamInfo]) {
    def isStreaming: Boolean = input.isDefined || output.isDefined
  }

  private def opStreams(w: TsWriter, model: Model, op: OperationShape): OpStreams = OpStreams(
    input = streamInfo(w, model, model.expectShape(op.getInputShape, classOf[StructureShape])),
    output = streamInfo(w, model, model.expectShape(op.getOutputShape, classOf[StructureShape])),
  )

  private def serviceOperations(model: Model, service: ServiceShape): List[OperationShape] =
    service
      .getOperations
      .asScala
      .toList
      .map(model.expectShape(_, classOf[OperationShape]))
      .sortBy(_.getId.toString)

  private def hasStreamingOperation(w: TsWriter, model: Model, service: ServiceShape): Boolean =
    serviceOperations(model, service).exists(opStreams(w, model, _).isStreaming)

  /** Emits the expression that adapts a stream of typed elements into the stream of values the
    * transport frames — i.e. the encode half. Binary streams pass through untouched; a JSON stream
    * (ndjson or server-sent events) has each element put in its wire form by the union's codec, as
    * a unary body would be, and the transport serialises what comes out.
    */
  private def streamEncodeExpr(info: StreamInfo, expr: String): String =
    info.framing match {
      case _: StreamFraming.Binary => expr
      case j: StreamFraming.Json   => s"wireMapStream($expr, encode${j.shapeName})"
    }

  /** Emits the expression that decodes and validates the transport's stream of already-framed
    * values. Binary streams are `Uint8Array` chunks and need no parsing; a JSON stream gets each
    * element decoded from its wire form and checked against the union schema, so a malformed
    * element fails at the element, naming the operation.
    */
  private def streamDecodeExpr(info: StreamInfo, expr: String, opLabel: String): String =
    info.framing match {
      case _: StreamFraming.Binary => s"$expr as AsyncIterable<Uint8Array>"
      case j: StreamFraming.Json   =>
        s"decodeStream($expr, { parse: (value: unknown) => ${j.schemaExpr}.parse(decode${j.shapeName}(value)) }, $opLabel)"
    }

  // --------------------------------------------------------------------------
  // Errors
  // --------------------------------------------------------------------------

  private def writeErrorClass(w: TsWriter, shape: StructureShape): Unit = {
    val name = w.tsName(shape)
    val className = name + "Error"
    val hasMessage = shape.getAllMembers.asScala.contains("message")
    val msgExpr =
      if (hasMessage)
        s"payload.message"
      else
        jsString(name)
    val status = errorStatusCode(shape)
    w.block(s"export class $className extends Error {", "}") {
      w.line(s"readonly payload: $name")
      // HTTP status + shape name that the real transport maps this error to.
      // The Storybook mock server (below) reads these off a thrown error to
      // synthesise the matching HTTP response.
      w.line(s"static readonly status = $status")
      w.line(s"static readonly errorType = ${jsString(name)}")
      w.line(s"readonly status = $status")
      w.line(s"readonly errorType = ${jsString(name)}")
      w.block(s"constructor(payload: $name) {", "}") {
        w.line(s"super($msgExpr)")
        w.line(s"this.name = ${jsString(className)}")
        w.line("this.payload = payload")
      }
    }
    w.line("")
  }

  /** Status code for an error: `@httpError(N)` wins; otherwise the default for `@error("client")`
    * (400) or `@error("server")` (500).
    */
  private def errorStatusCode(shape: StructureShape): Int =
    shape
      .getTrait(classOf[HttpErrorTrait])
      .toScala
      .map(_.getCode)
      .getOrElse(shape.expectTrait(classOf[ErrorTrait]).getDefaultHttpStatusCode)

  // --------------------------------------------------------------------------
  // Transport
  // --------------------------------------------------------------------------

  private def writeTransport(w: TsWriter): Unit = {
    w.line("/** Per-operation transport options. The generator threads these straight")
    w.line(" * through to `Transport.request` without inspecting them, so concrete")
    w.line(" * transports can layer in framework-specific knobs (e.g. axios's")
    w.line(" * `skipErrorPopup`) without the codegen having to know about them.")
    w.line(" */")
    w.block("export interface TransportOptions {", "}") {
      w.line("[key: string]: unknown")
    }
    w.line("")
    w.block("export interface TransportRequest {", "}") {
      w.line("/** Stable identifier for the operation issuing this request, of the form")
      w.line(" * `<ServiceClient>.<methodName>`. Middleware (e.g. tracing) can use this")
      w.line(" * to name a span or attach attributes; the underlying HTTP transport")
      w.line(" * ignores it.")
      w.line(" */")
      w.line("operation: string")
      w.line("method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'")
      w.line("url: string")
      w.line("/** A list value is sent as the parameter repeated, once per element. */")
      w.line("query?: Record<string, string | number | boolean | readonly string[] | undefined>")
      w.line("headers?: Record<string, string>")
      w.line("body?: unknown")
      w.line("options?: TransportOptions")
    }
    w.line("")
    w.block("export interface TransportResponse {", "}") {
      w.line("status: number")
      w.line("body: unknown")
      w.line("/** Lowercased header names → values. Concrete transports must lowercase")
      w.line(" * keys so the client's error-dispatch lookup is case-insensitive.")
      w.line(" */")
      w.line("headers: Record<string, string>")
    }
    w.line("")
    w.block("export interface Transport {", "}") {
      w.line("request(req: TransportRequest): Promise<TransportResponse>")
    }
    w.line("")
    w.line("/** Thrown when the server returned a non-2xx status the client couldn't")
    w.line(" * match against the operation's declared errors — either no error is")
    w.line(" * declared for that status, or `X-Error-Type` didn't name any of them.")
    w.line(" * 401s are converted to `UnauthenticatedError` by the transport before")
    w.line(" * the client sees them, so they don't surface here.")
    w.line(" */")
    w.block("export class UnexpectedResponseError extends Error {", "}") {
      w.line("readonly operation: string")
      w.line("readonly status: number")
      w.line("readonly body: unknown")
      w.line("readonly headers: Record<string, string>")
      w.block(
        "constructor(operation: string, status: number, body: unknown, headers: Record<string, string>) {",
        "}",
      ) {
        w.line("super(operation + ' -> ' + status)")
        w.line("this.name = 'UnexpectedResponseError'")
        w.line("this.operation = operation")
        w.line("this.status = status")
        w.line("this.body = body")
        w.line("this.headers = headers")
      }
    }
    w.line("")
    w.line("/** Thrown by the transport for any 401. The auth middleware sits in front")
    w.line(" * of every route and isn't modelled per-operation, so 401 is never in an")
    w.line(" * operation's `errors: [...]` — we surface it as a typed error here.")
    w.line(" */")
    w.block("export class UnauthenticatedError extends Error {", "}") {
      w.line("readonly operation: string")
      w.block("constructor(operation: string) {", "}") {
        w.line("super(operation + ' -> 401 Unauthorized')")
        w.line("this.name = 'UnauthenticatedError'")
        w.line("this.operation = operation")
      }
    }
    w.line("")
  }

  /** Emits the streaming half of the transport contract, plus the `decodeStream` helper the
    * generated clients/mocks use to type ndjson elements. Only emitted when the model actually has
    * a streaming operation, so a model without one produces byte-identical output to before.
    */
  private def writeStreamTransport(w: TsWriter): Unit = {
    w.line("/** A request whose body is streamed, whose response body is streamed,")
    w.line(" * or both. Everything a `TransportRequest` carries applies here too;")
    w.line(" * what is added is the framing and the outgoing stream.")
    w.line(" *")
    w.line(" * `streamEncoding` tells the transport how to frame each direction that")
    w.line(" * streams — the generator derives it from the shape of the `@streaming`")
    w.line(" * member, so the transport never has to guess from a content type:")
    w.line(" *")
    w.line(" *   - `'ndjson'` — one JSON value per line (`application/x-ndjson`).")
    w.line(" *     Outgoing: `JSON.stringify` each element, terminate each with a")
    w.line(" *     newline. Incoming: split on newlines, skip blank lines, and")
    w.line(" *     `JSON.parse` each one. The generated client applies the element")
    w.line(" *     schema on top of whatever is yielded here.")
    w.line(" *   - `'sse'` — one server-sent event per JSON value")
    w.line(" *     (`text/event-stream`), for a union marked `@serverSentEvents`.")
    w.line(" *     Outgoing: each element's JSON as an event's `data`, then the")
    w.line(" *     terminator event if `requestStreamTerminator` is set. Incoming:")
    w.line(" *     `JSON.parse` each event's `data`, skipping comments, other fields,")
    w.line(" *     keep-alives and the `responseStreamTerminator` event.")
    w.line(" *   - `'binary'` — the body verbatim")
    w.line(" *     (`application/octet-stream`), as `Uint8Array` chunks. Chunk")
    w.line(" *     boundaries carry no meaning in either direction.")
    w.line(" */")
    w.line("export type StreamEncoding = 'ndjson' | 'sse' | 'binary'")
    w.line("")
    w.block("export interface StreamTransportRequest extends TransportRequest {", "}") {
      w.line("/** Framing for the request body, when it streams. `undefined` means the")
      w.line(" * request body is an ordinary (unary) JSON body carried by `body`.")
      w.line(" */")
      w.line("requestStreamEncoding?: StreamEncoding")
      w.line("/** Framing the response body is expected in, when it streams.")
      w.line(" * `undefined` means an ordinary JSON response body.")
      w.line(" */")
      w.line("responseStreamEncoding?: StreamEncoding")
      w.line("/** For an `'sse'` request: the data of one more event to send after")
      w.line(" * the last value (the union's `@serverSentEvents(terminator:)`).")
      w.line(" */")
      w.line("requestStreamTerminator?: string")
      w.line("/** For an `'sse'` response: the data of the event the server sends")
      w.line(" * after the last value, which is not itself a value and is skipped.")
      w.line(" */")
      w.line("responseStreamTerminator?: string")
      w.line("/** The outgoing stream, present exactly when `requestStreamEncoding` is.")
      w.line(" * For `'ndjson'` and `'sse'` these are the values to serialise; for")
      w.line(" * `'binary'`, `Uint8Array` chunks. Overrides `body`.")
      w.line(" */")
      w.line("stream?: AsyncIterable<unknown>")
    }
    w.line("")
    w.block("export interface StreamTransportResponse {", "}") {
      w.line("status: number")
      w.line("/** Lowercased header names → values, as on `TransportResponse`. */")
      w.line("headers: Record<string, string>")
      w.line("/** The deframed response body, present exactly when the operation")
      w.line(" * streams its output and the status was 2xx. Elements are the values")
      w.line(" * `responseStreamEncoding` describes — already `JSON.parse`d for")
      w.line(" * `'ndjson'` and `'sse'`, raw `Uint8Array` chunks for `'binary'`. The")
      w.line(" * generated client validates those elements against the operation's")
      w.line(" * schema.")
      w.line(" */")
      w.line("stream?: AsyncIterable<unknown>")
      w.line("/** The fully-read body, for a non-2xx response (so declared errors can")
      w.line(" * be parsed and thrown) or an operation with a unary response body.")
      w.line(" */")
      w.line("body?: unknown")
    }
    w.line("")
    w.line("/** The streaming counterpart of `Transport`. A transport that backs a")
    w.line(" * service with streaming operations implements both; the generated client")
    w.line(" * takes whichever it needs.")
    w.line(" *")
    w.line(" * A streamed response commits its HTTP status before the first element is")
    w.line(" * pulled, so a failure that happens mid-stream cannot arrive as a status.")
    w.line(" * Such failures are modelled as members of the streamed union instead —")
    w.line(" * the protocol expects a terminal member (e.g. `completed` / `failed`) —")
    w.line(" * so `requestStream` rejecting means the request failed *before* the")
    w.line(" * stream started, and an error after that surfaces as an element or as an")
    w.line(" * exception thrown while iterating.")
    w.line(" */")
    w.block("export interface StreamTransport {", "}") {
      w.line("requestStream(req: StreamTransportRequest): Promise<StreamTransportResponse>")
    }
    w.line("")
    w.line("/** Thrown when a streamed response was expected but the transport didn't")
    w.line(" * produce one (a transport bug, or a server that answered 2xx with a")
    w.line(" * non-streamed body). */")
    w.block("export class MissingStreamError extends Error {", "}") {
      w.line("readonly operation: string")
      w.block("constructor(operation: string) {", "}") {
        w.line("super(operation + ' -> response did not carry a stream')")
        w.line("this.name = 'MissingStreamError'")
        w.line("this.operation = operation")
      }
    }
    w.line("")
    w.line("/** Validates each element of a deframed ndjson stream against the")
    w.line(" * operation's element schema, lazily — an element is parsed as it is")
    w.line(" * pulled, so a long-running stream doesn't buffer and a malformed element")
    w.line(" * fails at that element rather than silently truncating the stream. */")
    w.line("export const decodeStream = async function* <T>(")
    w.line("  source: AsyncIterable<unknown>,")
    w.line("  schema: { parse: (value: unknown) => T },")
    w.line("  operation: string,")
    // A generator's declared return type has to be a generator type —
    // `AsyncIterable<T>` alone is rejected — but `AsyncGenerator` is assignable
    // to `AsyncIterable`, so callers still see a plain async iterable.
    w.block("): AsyncGenerator<T, void, undefined> {", "}") {
      w.block("for await (const element of source) {", "}") {
        w.tryCatchBlock("err") {
          w.line("yield schema.parse(element)")
        } {
          w.line("throw new StreamDecodeError(operation, element, err)")
        }
      }
    }
    w.line("")
    w.line("/** Applies `f` to each element of a stream as it is pulled. */")
    w.line("const wireMapStream = async function* <A, B>(")
    w.line("  source: AsyncIterable<A>,")
    w.line("  f: (element: A) => B,")
    w.block("): AsyncGenerator<B, void, undefined> {", "}") {
      w.line("for await (const element of source) yield f(element)")
    }
    w.line("")
    w.line("/** An empty stream, used when a request that declares a streamed input")
    w.line(" * arrives without one (e.g. a mock invoked with no stream). */")
    w.line(
      "export const emptyStream = async function* (): AsyncGenerator<never, void, undefined> {}"
    )
    w.line("")
    w.line("/** Thrown while iterating a streamed response whose element failed the")
    w.line(" * operation's schema. Carries the offending element so a caller can log")
    w.line(" * what actually came over the wire. */")
    w.block("export class StreamDecodeError extends Error {", "}") {
      w.line("readonly operation: string")
      w.line("readonly element: unknown")
      // `cause` is declared by `Error` itself under ES2022 libs, so it is set
      // through the constructor's options bag rather than redeclared here.
      w.block("constructor(operation: string, element: unknown, cause: unknown) {", "}") {
        w.line("super(operation + ' -> could not decode a stream element', { cause })")
        w.line("this.name = 'StreamDecodeError'")
        w.line("this.operation = operation")
        w.line("this.element = element")
      }
    }
    w.line("")
  }

  // --------------------------------------------------------------------------
  // Service clients
  // --------------------------------------------------------------------------

  private def writeClient(
    w: TsWriter,
    model: Model,
    service: ServiceShape,
    paths: PathResolver,
  ): Unit = {
    val svcName = service.getId.getName
    val ops = serviceOperations(model, service)
    // Each half of the transport is taken only when some operation actually
    // uses it: a service that streams everything never calls `Transport.request`,
    // and one that streams nothing never calls `requestStream`. Requiring both
    // up front (rather than an optional second argument) keeps a streaming call
    // from failing at runtime on a transport that can't serve it, but requiring
    // a half nothing calls would just force callers to invent a stub.
    val streaming = ops.exists(opStreams(w, model, _).isStreaming)
    val unary = ops.exists(!opStreams(w, model, _).isStreaming)
    // A service with no operations at all still takes the unary half: an empty
    // constructor would make the client look like it needs no transport, and a
    // later operation would silently change its signature.
    val takesUnary = unary || !streaming
    val params =
      List(
        Option.when(takesUnary)("transport: Transport"),
        Option.when(streaming)("streamTransport: StreamTransport"),
      ).flatten.mkString(", ")
    w.block(s"export class ${svcName}Client {", "}") {
      // Avoid parameter-property syntax — `tsc --erasableSyntaxOnly` rejects it.
      if (takesUnary)
        w.line("private readonly transport: Transport")
      if (streaming)
        w.line("private readonly streamTransport: StreamTransport")
      w.block(s"constructor($params) {", "}") {
        if (takesUnary)
          w.line("this.transport = transport")
        if (streaming)
          w.line("this.streamTransport = streamTransport")
      }
      ops.foreach(op => writeOperation(w, model, op, service, paths))
    }
    w.line("")
  }

  private def writeOperation(
    w: TsWriter,
    model: Model,
    op: OperationShape,
    service: ServiceShape,
    paths: PathResolver,
  ): Unit = {
    val svcName = service.getId.getName
    val opName = op.getId.getName
    val methodName = lowerFirst(opName)
    val http = op
      .getTrait(classOf[HttpTrait])
      .toScala
      .getOrElse(sys.error(s"operation ${op.getId} has no @http trait — cannot emit client method"))
    val input = model.expectShape(op.getInputShape, classOf[StructureShape])
    val output = model.expectShape(op.getOutputShape, classOf[StructureShape])

    val inputMembers = input.getAllMembers.asScala.toList
    val labelMembers = inputMembers.filter(_._2.hasTrait(classOf[HttpLabelTrait]))
    val queryMembers = inputMembers.filter(_._2.hasTrait(classOf[HttpQueryTrait]))
    val headerMembers = inputMembers.filter(_._2.hasTrait(classOf[HttpHeaderTrait]))
    val payloadMembers = inputMembers.filter(_._2.hasTrait(classOf[HttpPayloadTrait]))
    if (payloadMembers.size > 1)
      sys.error(s"operation ${op.getId} has multiple @httpPayload members; smithy forbids this")
    val payloadMember = payloadMembers.headOption

    val queryParamsMember = inputMembers.find(_._2.hasTrait(classOf[HttpQueryParamsTrait]))
    val prefixHeadersMember = inputMembers.find(_._2.hasTrait(classOf[HttpPrefixHeadersTrait]))
    // `@http(uri: "/path?fixed=value&flag")` — part of the URL itself: a value-less `flag` has
    // no spelling in `query`, where an empty string is `flag=`.
    val queryLiterals = http.getUri.getQueryLiterals.asScala.toList.sortBy(_._1)
    val greedyLabel = http.getUri.getGreedyLabel.toScala.map(_.getContent)

    val inputTypeName = w.tsName(input)
    val isUnitInput = input.getId == UnitId

    val streams = opStreams(w, model, op)
    val opLabel = jsString(s"${svcName}Client.$methodName")

    w.line("")
    // The operation's own docs and the streaming notes share one comment block,
    // rather than stacking two `/** */`s on the same method.
    val docLines =
      op.getTrait(classOf[DocumentationTrait])
        .toScala
        .map(_.getValue.replace("\n", " ").trim)
        .toList
    val streamNotes =
      streams
        .input
        .map { in =>
          s"Streams `${in.memberName}` to the server as ${framingLabel(in)}."
        }
        .toList ++
        streams
          .output
          .map { out =>
            s"Resolves once the response status is known; `${out.memberName}` then yields ${framingLabel(out)} lazily, so iterate it to consume the response."
          }
          .toList
    (docLines ++ streamNotes) match {
      case Nil           => ()
      case single :: Nil => w.lit(s"/** $single */")
      case many          =>
        w.line("/**")
        many.foreach(note => w.lit(s" * $note"))
        w.line(" */")
    }
    // The generated input/output types already declare a streamed member as an
    // `AsyncIterable` (see `writeStructure`), so they describe a streaming
    // operation's signature as-is.
    val resultType = returnType(w, output)
    val signature =
      if (isUnitInput)
        s"async $methodName(opts?: TransportOptions): Promise<$resultType> {"
      else
        s"async $methodName(input: $inputTypeName, opts?: TransportOptions): Promise<$resultType> {"

    w.block(signature, "}") {
      // urlExpression returns a TS template literal containing `${...}`; route
      // through the $L (literal) formatter so smithy's `$`-parser doesn't try
      // to interpret it.
      val labels =
        labelMembers.map { case (memberName, member) =>
          memberName -> WireCodec.labelExpr(
            model,
            member,
            s"input.$memberName",
            greedyLabel.contains(memberName),
          )
        }.toMap
      w.line(
        "const url = $L",
        urlExpression(
          paths.resolve(service, op, http.getUri, labelMembers.map(_._1).toSet),
          labels,
          queryLiterals,
        ),
      )

      // Bound members are skipped when absent — `!= null`, so an explicit `null` (which the
      // protocol treats as absent too) isn't sent as the string "null".
      val hasQuery = queryMembers.nonEmpty || queryParamsMember.isDefined
      if (hasQuery) {
        w.line("const query: Record<string, string | readonly string[] | undefined> = {}")
        // The map first, so a member bound with `@httpQuery` wins over a map entry of the same name.
        queryParamsMember.foreach { case (memberName, _) =>
          w.lit(
            s"if (input.$memberName != null) for (const [k, v] of Object.entries(input.$memberName)) query[k] = v"
          )
        }
        queryMembers.foreach { case (memberName, member) =>
          val queryName = member.expectTrait(classOf[HttpQueryTrait]).getValue
          w.lit(
            s"if (input.$memberName != null) query[${jsString(queryName)}] = ${WireCodec.queryValueExpr(model, member, s"input.$memberName")}"
          )
        }
      }
      val hasHeaders = headerMembers.nonEmpty || prefixHeadersMember.isDefined
      if (hasHeaders) {
        w.line("const headers: Record<string, string> = {}")
        prefixHeadersMember.foreach { case (memberName, member) =>
          val prefix = member.expectTrait(classOf[HttpPrefixHeadersTrait]).getValue
          w.lit(
            s"if (input.$memberName != null) for (const [k, v] of Object.entries(input.$memberName)) headers[${jsString(prefix)} + k] = v"
          )
        }
        headerMembers.foreach { case (memberName, member) =>
          val headerName = member.expectTrait(classOf[HttpHeaderTrait]).getValue
          w.lit(
            s"if (input.$memberName != null) headers[${jsString(headerName)}] = ${WireCodec.headerValueExpr(model, member, s"input.$memberName")}"
          )
        }
      }

      // A streaming input member *is* the body, so it travels as `stream`
      // rather than `body` and never joins the JSON payload.
      val bodyExpr: String =
        streams.input match {
          case Some(_) => "undefined"
          case None    =>
            WireCodec
              .bodyEncodeExpr(
                w,
                model,
                payloadMember.map(_._2),
                WireCodec.requestBodyMembers(input),
                "input",
              )
              .getOrElse("undefined")
        }

      val call =
        if (streams.isStreaming)
          "const res = await this.streamTransport.requestStream({"
        else
          "const res = await this.transport.request({"
      w.block(call, "})") {
        w.line(s"operation: $opLabel,")
        w.line(s"method: ${jsString(http.getMethod)},")
        w.line("url,")
        if (hasQuery)
          w.line("query,")
        // A blob's `@mediaType` labels the bytes. Lowercase, so it replaces the transport's default
        // `content-type` for a binary stream rather than joining it.
        val mediaType = streams.input.map(_.framing).collect {
          case StreamFraming.Binary(Some(mt)) => s"'content-type': ${jsString(mt)}"
        }
        (hasHeaders, mediaType) match {
          case (true, Some(mt))  => w.line(s"headers: { ...headers, $mt },")
          case (false, Some(mt)) => w.line(s"headers: { $mt },")
          case (true, None)      => w.line("headers,")
          case (false, None)     => ()
        }
        w.lit(s"body: $bodyExpr,")
        streams.input.foreach { in =>
          w.line(s"requestStreamEncoding: ${jsString(in.framing.encoding)},")
          in.framing.terminator.foreach(t => w.line(s"requestStreamTerminator: ${jsString(t)},"))
          w.line(s"stream: ${streamEncodeExpr(in, s"input.${in.memberName}")},")
        }
        streams.output.foreach { out =>
          w.line(s"responseStreamEncoding: ${jsString(out.framing.encoding)},")
          out
            .framing
            .terminator
            .foreach(t => w.line(s"responseStreamTerminator: ${jsString(t)},"))
        }
        w.line("options: opts,")
      }

      // 2xx → parse the typed output and return. Non-2xx falls through to
      // the error dispatch below; we never run the output parser on an error
      // body (which is what produced the bogus Zod "expected: object" error
      // when an undeclared status came back). The transport already throws
      // `UnauthenticatedError` for 401 itself, so we won't see one here.
      // An output with an `@httpResponseCode` member may succeed with any non-error code (a 3xx,
      // say), so only 4xx/5xx go to error dispatch.
      val successEnd =
        if (output.getAllMembers.asScala.values.exists(_.hasTrait(classOf[HttpResponseCodeTrait])))
          400
        else
          300
      w.block(s"if (res.status >= 200 && res.status < $successEnd) {", "}") {
        streams.output match {
          case None if output.getId == UnitId => w.line("return undefined")
          case None      => w.line(s"return decode${w.tsName(output)}Response(res)")
          case Some(out) => writeStreamingResponseParse(w, model, output, out, opLabel)
        }
      }

      // Bucket declared errors by status. One error per status → throw directly;
      // multiple → disambiguate by `X-Error-Type` (unqualified shape name,
      // emitted by smithy4s and by our hand-rolled routes).
      val errsByStatus = op
        .getErrors(service)
        .asScala
        .toList
        .map(model.expectShape(_, classOf[StructureShape]))
        .groupBy(errorStatusCode)
      errsByStatus.toList.sortBy(_._1).foreach { case (code, candidates) =>
        w.block(s"if (res.status === $code) {", "}") {
          if (candidates.sizeIs == 1) {
            val name = w.tsName(candidates.head)
            w.line(s"throw new ${name}Error(decode${name}Response(res))")
          } else {
            w.line("const errorType = res.headers['x-error-type']")
            candidates.foreach { err =>
              val name = w.tsName(err)
              w.line(
                s"if (errorType === ${jsString(name)}) throw new ${name}Error(decode${name}Response(res))"
              )
            }
          }
        }
      }

      w.line(s"throw new UnexpectedResponseError($opLabel, res.status, res.body, res.headers)")
    }
  }

  /** Resolves an operation's final URI path: parses the `@http` URI into [[PathSegment]]s, runs the
    * extensions over it, and validates what they hand back.
    *
    * Both the client and the Storybook mocks resolve through this one place, so an extension cannot
    * make them disagree about a route.
    */
  private[ts] final class PathResolver(extensions: List[TsCodegenExtension]) {

    /** The `@http` URI as segments, before any extension sees it. */
    private def parse(uri: software.amazon.smithy.model.pattern.UriPattern): List[PathSegment] =
      uri
        .getSegments
        .asScala
        .toList
        .map { seg =>
          if (seg.isLiteral)
            PathSegment.Literal(seg.getContent)
          else
            PathSegment.Label(seg.getContent)
        }

    /** Applies every extension in turn, then checks the result is renderable: each label must be
      * bound with `@httpLabel`, and a literal must not smuggle in a `/` (which would read as one
      * segment to the client but never match the mock router's segment-by-segment comparison).
      */
    def resolve(
      service: ServiceShape,
      operation: OperationShape,
      uri: software.amazon.smithy.model.pattern.UriPattern,
      labelNames: Set[String],
    ): List[PathSegment] = {
      val out =
        extensions.foldLeft(parse(uri))((path, ext) => ext.transformPath(service, operation, path))
      out.foreach {
        case PathSegment.Label(name) =>
          if (!labelNames.contains(name))
            sys.error(
              s"URI references label {$name} but no input member is bound with @httpLabel"
            )
        case PathSegment.Literal(value) =>
          if (value.contains("/"))
            sys.error(
              s"path segment '$value' for ${operation.getId} contains '/': " +
                "return several Literal segments instead of one with a slash in it"
            )
      }
      out
    }

  }

  /** The client's URL as a TS template literal, e.g. `` `/things/${encodeURIComponent(...)}` ``. */
  private def urlExpression(
    path: List[PathSegment],
    labels: Map[String, String],
    queryLiterals: List[(String, String)],
  ): String = {
    val rendered =
      path.map {
        case PathSegment.Literal(value) => "/" + value
        case PathSegment.Label(name)    => s"/$${${labels(name)}}"
      }.mkString
    // An empty path — an extension returned Nil, or the URI was just `/` — is the root.
    val body =
      if (rendered.isEmpty)
        "/"
      else
        rendered
    val query =
      if (queryLiterals.isEmpty)
        ""
      else
        queryLiterals
          .map {
            case (name, "")    => percentEncode(name)
            case (name, value) => percentEncode(name) + "=" + percentEncode(value)
          }
          .mkString("?", "&", "")
    "`" + body + query + "`"
  }

  private def returnType(w: TsWriter, output: StructureShape): String =
    if (output.getId == UnitId)
      "void"
    else
      w.tsName(output)

  /** Human-readable framing, for the generated doc comments. */
  private def framingLabel(info: StreamInfo): String =
    info.framing match {
      case _: StreamFraming.Binary => "raw bytes"
      case n: StreamFraming.Ndjson => s"newline-delimited `${n.shapeName}` values"
      case e: StreamFraming.Sse    => s"`${e.shapeName}` values as server-sent events"
    }

  /** Emits the `return ...` for a successful streamed response: the non-streaming members are
    * re-hydrated from headers / status exactly as in the unary case, and the streaming member is
    * filled from `res.stream` (schema-checked per element for ndjson).
    *
    * The result is deliberately *not* run through the output schema: the stream member would fail
    * validation (it isn't an array, and consuming it to check would defeat streaming), so the bound
    * members are validated individually and the object is assembled by hand.
    */
  private def writeStreamingResponseParse(
    w: TsWriter,
    model: Model,
    output: StructureShape,
    out: StreamInfo,
    opLabel: String,
  ): Unit = {
    val members = output.getAllMembers.asScala.toList
    val headerMembers = members.filter(_._2.hasTrait(classOf[HttpHeaderTrait]))
    val responseCodeMembers = members.filter(_._2.hasTrait(classOf[HttpResponseCodeTrait]))
    val outName = w.tsName(output)

    w.ifBlock("res.stream === undefined") {
      w.line(s"throw new MissingStreamError($opLabel)")
    }
    w.line("const raw: Record<string, unknown> = {}")
    headerMembers.foreach { case (memberName, member) =>
      val headerName = member.expectTrait(classOf[HttpHeaderTrait]).getValue
      val target = model.expectShape(member.getTarget)
      val lookup = s"res.headers[${jsString(headerName.toLowerCase)}]"
      w.block(s"if ($lookup !== undefined) {", "}") {
        w.lit(
          s"raw[${jsString(memberName)}] = ${inlineSchemaExpr(w, target, member)}.parse(${WireCodec.headerDecodeExpr(model, member, lookup)})"
        )
      }
    }
    responseCodeMembers.foreach { case (memberName, _) =>
      w.line(s"raw[${jsString(memberName)}] = res.status")
    }
    w.line(
      s"raw[${jsString(out.memberName)}] = ${streamDecodeExpr(out, "res.stream", opLabel)}"
    )
    w.line(s"return raw as $outName")
  }

  // --------------------------------------------------------------------------
  // Storybook mock server
  // --------------------------------------------------------------------------
  //
  // For each service we emit a typed `XxxServiceHandlers` interface (one method
  // per operation, decoded input -> typed output / thrown error) plus a
  // `XxxServiceMock` descriptor that carries the per-operation routing, input
  // decoding and output encoding. A small shared runtime (`mockService`) turns a
  // partial set of handlers into a request matcher that a Storybook axios mock
  // adapter can consume.

  private def writeMockRuntime(w: TsWriter, streaming: Boolean): Unit = {
    w.line("/** A request as seen by the mock server: the URL path already has any")
    w.line(" * proxy prefix stripped, `pathParams` holds decoded `@httpLabel`")
    w.line(" * values, `query` the parsed query string, `body` the parsed JSON body. */")
    w.block("export interface MockRequest {", "}") {
      w.line("method: string")
      w.line("path: string")
      w.line("pathParams: Record<string, string>")
      w.line("query: Record<string, string | undefined>")
      w.line("body: unknown")
      if (streaming) {
        w.line("/** The deframed request body, for an operation that streams its input.")
        w.line(" * Elements are `JSON.parse`d values for an ndjson or sse stream and")
        w.line(" * `Uint8Array` chunks for a binary one — the same shape the streaming")
        w.line(" * transport yields, so a mock and a real server see the same thing. */")
        w.line("stream?: AsyncIterable<unknown>")
      }
    }
    w.line("")
    w.block("export interface MockResponse {", "}") {
      w.line("status: number")
      w.line("body: unknown")
      w.line("headers: Record<string, string>")
      if (streaming) {
        w.line("/** Set instead of `body` when the operation streams its output; the")
        w.line(" * adapter frames these elements per `streamEncoding`. */")
        w.line("stream?: AsyncIterable<unknown>")
        w.line("/** Framing for `stream`, mirroring `StreamEncoding` on the transport. */")
        w.line("streamEncoding?: 'ndjson' | 'sse' | 'binary'")
        w.line("/** For an `'sse'` stream: the data of the event to send after the last")
        w.line(" * element, if the union declares one. */")
        w.line("streamTerminator?: string")
      }
    }
    w.line("")
    w.line("/** One URI path segment of an operation's route: a literal or a")
    w.line(" * `@httpLabel` capture. */")
    w.line(
      "export type MockUriSegment = { readonly literal: string } | { readonly label: string }"
    )
    w.line("")
    w.line("/** A single operation's route + codecs. `decodeInput` turns a matched")
    w.line(" * request into the operation's typed input; `encodeBody` extracts the")
    w.line(" * HTTP response body from the typed output. */")
    w.block("export interface MockOperation<Handlers> {", "}") {
      w.line("readonly key: keyof Handlers & string")
      w.line("readonly method: string")
      w.line("readonly segments: readonly MockUriSegment[]")
      w.line("readonly decodeInput: (req: MockRequest) => unknown")
      w.line("readonly encodeBody: (output: unknown) => unknown")
      if (streaming) {
        w.line("/** Framing of this operation's streamed output, when it streams one.")
        w.line(" * `undefined` for an ordinary unary operation. */")
        w.line("readonly responseStreamEncoding?: 'ndjson' | 'sse' | 'binary'")
        w.line("/** The terminator event's data, for an `'sse'` output that declares one. */")
        w.line("readonly responseStreamTerminator?: string")
        w.line("/** Pulls the streamed member out of a handler's returned output, for")
        w.line(" * an operation that streams its output. */")
        w.line("readonly encodeStream?: (output: unknown) => AsyncIterable<unknown>")
      }
    }
    w.line("")
    w.block("export interface MockServiceDescriptor<Handlers> {", "}") {
      w.line("readonly serviceName: string")
      w.line("readonly operations: readonly MockOperation<Handlers>[]")
    }
    w.line("")
    w.line("/** Thrown from a handler to return an arbitrary HTTP status/body that")
    w.line(" * isn't one of the operation's declared, typed errors (e.g. a 503 to")
    w.line(" * exercise a generic failure path). */")
    w.block("export class MockHttpError extends Error {", "}") {
      w.line("readonly status: number")
      w.line("readonly body: unknown")
      w.block("constructor(status: number, body: unknown = { message: 'Error' }) {", "}") {
        w.line("super('MockHttpError ' + status)")
        w.line("this.name = 'MockHttpError'")
        w.line("this.status = status")
        w.line("this.body = body")
      }
    }
    w.line("")
    w.line("/** A matched, ready-to-run mock operation: everything the shared adapter")
    w.line(" * needs to invoke one handler and encode its result. */")
    w.block("export interface MockMatch {", "}") {
      w.line("run: (req: MockRequest) => Promise<MockResponse>")
    }
    w.line("")
    w.line("/** A bound service mock: a matcher from HTTP method + path to a runnable")
    w.line(" * operation, or `null` when this service handles no such route. */")
    w.block("export interface BoundMockService {", "}") {
      w.line("serviceName: string")
      w.line("match: (method: string, path: string) => MockMatch | null")
    }
    w.line("")
    w.line("const matchSegments = (")
    w.line("  segments: readonly MockUriSegment[],")
    w.line("  path: string,")
    w.block("): Record<string, string> | null => {", "}") {
      w.line("const parts = path.split('/').filter((p) => p.length > 0)")
      w.line("if (parts.length !== segments.length) return null")
      w.line("const pathParams: Record<string, string> = {}")
      w.block("for (let i = 0; i < segments.length; i++) {", "}") {
        w.line("const seg = segments[i]")
        w.ifBlock("'literal' in seg") {
          w.line("if (seg.literal !== parts[i]) return null")
        }
        w.elseBlock {
          w.line("pathParams[seg.label] = decodeURIComponent(parts[i])")
        }
      }
      w.line("return pathParams")
    }
    w.line("")
    w.line("/** Binds a service descriptor to a partial set of handlers, producing a")
    w.line(" * matcher. An unimplemented operation that gets called throws, so stories")
    w.line(" * only implement the operations they exercise. A thrown generated error")
    w.line(" * (`XxxError`) is encoded to its declared status + `x-error-type`; a")
    w.line(" * `MockHttpError` to its status/body; anything else re-throws. */")
    w.line("export const mockService = <Handlers>(")
    w.line("  descriptor: MockServiceDescriptor<Handlers>,")
    w.line("  handlers: Partial<Handlers>,")
    w.block("): BoundMockService => ({", "})") {
      w.line("serviceName: descriptor.serviceName,")
      w.block("match: (method, path) => {", "},") {
        w.block("for (const op of descriptor.operations) {", "}") {
          w.line("if (op.method !== method) continue")
          w.line("const pathParams = matchSegments(op.segments, path)")
          w.line("if (pathParams === null) continue")
          w.line("const handler = handlers[op.key] as")
          w.line("  | ((input: unknown) => unknown | Promise<unknown>)")
          w.line("  | undefined")
          w.block("return {", "}") {
            w.block("run: async (req): Promise<MockResponse> => {", "},") {
              w.ifBlock("handler === undefined") {
                w.line(
                  "throw new Error(descriptor.serviceName + '.' + op.key + ' called but no mock handler was provided')"
                )
              }
              w.line("const input = op.decodeInput({ ...req, pathParams })")
              w.tryCatchBlock("err") {
                w.line("const output = await handler(input)")
                // A streaming operation's status commits before any element is
                // pulled, so the handler's stream is handed back unconsumed —
                // a handler that throws mid-stream surfaces while iterating,
                // exactly as it would against a real server.
                if (streaming)
                  w.ifBlock("op.encodeStream !== undefined") {
                    w.block("return {", "}") {
                      w.line("status: 200,")
                      w.line("body: op.encodeBody(output),")
                      w.line("headers: {},")
                      w.line("stream: op.encodeStream(output),")
                      w.line("streamEncoding: op.responseStreamEncoding,")
                      w.line(
                        "...(op.responseStreamTerminator !== undefined ? { streamTerminator: op.responseStreamTerminator } : {}),"
                      )
                    }
                  }
                w.line("return { status: 200, body: op.encodeBody(output), headers: {} }")
              } {
                w.ifBlock("err instanceof MockHttpError") {
                  w.line("return { status: err.status, body: err.body, headers: {} }")
                }
                w.ifBlock("isTypedMockError(err)") {
                  w.line(
                    "return { status: err.status, body: err.payload, headers: { 'x-error-type': err.errorType } }"
                  )
                }
                w.line("throw err")
              }
            }
          }
        }
        w.line("return null")
      }
    }
    w.line("")
    w.block("interface TypedMockError {", "}") {
      w.line("status: number")
      w.line("errorType: string")
      w.line("payload: unknown")
    }
    w.line("")
    w.line("const isTypedMockError = (err: unknown): err is TypedMockError =>")
    w.line("  err instanceof Error &&")
    w.line("  typeof (err as { status?: unknown }).status === 'number' &&")
    w.line("  typeof (err as { errorType?: unknown }).errorType === 'string' &&")
    w.line("  'payload' in err")
    w.line("")
    w.line("/** Routes a request across several bound services, returning the first")
    w.line(" * match. Stories pass the mocks for whichever services a screen touches. */")
    // Body is the returned arrow's arrow — its own indent sits under the `=>`
    // continuation, which isn't a `block`, so keep this one hand-indented.
    w.line("export const mockServices = (")
    w.line("  ...services: readonly BoundMockService[]")
    w.line("): ((method: string, path: string) => MockMatch | null) =>")
    w.line("  (method, path) => {")
    w.line("    for (const svc of services) {")
    w.line("      const m = svc.match(method, path)")
    w.line("      if (m !== null) return m")
    w.line("    }")
    w.line("    return null")
    w.line("  }")
    w.line("")
  }

  private def writeMockService(
    w: TsWriter,
    model: Model,
    service: ServiceShape,
    paths: PathResolver,
  ): Unit = {
    val svcName = service.getId.getName
    val ops = serviceOperations(model, service)

    // Handlers interface: one method per operation, decoded input -> typed
    // output (or a thrown error). Unit inputs take no argument.
    w.block(s"export interface ${svcName}Handlers {", "}") {
      ops.foreach { op =>
        val opName = op.getId.getName
        val methodName = lowerFirst(opName)
        val input = model.expectShape(op.getInputShape, classOf[StructureShape])
        val output = model.expectShape(op.getOutputShape, classOf[StructureShape])
        val isUnitInput = input.getId == UnitId
        // A handler mirrors the client method. The generated input/output types
        // already declare streamed members as `AsyncIterable`s, so a story
        // implements such an operation as an async generator with no ceremony.
        val outTy = returnType(w, output)
        val ret =
          if (outTy == "void")
            "void | Promise<void>"
          else
            s"$outTy | Promise<$outTy>"
        op.getTrait(classOf[DocumentationTrait]).toScala.foreach { t =>
          w.lit(s"/** ${t.getValue.replace("\n", " ").trim} */")
        }
        if (isUnitInput)
          w.line(s"$methodName(): $ret")
        else
          w.line(s"$methodName(input: ${w.tsName(input)}): $ret")
      }
    }
    w.line("")

    w.block(s"export const ${svcName}Mock: MockServiceDescriptor<${svcName}Handlers> = {", "}") {
      w.line(s"serviceName: ${jsString(svcName)},")
      w.block("operations: [", "],") {
        ops.foreach(op => writeMockOperation(w, model, op, service, paths))
      }
    }
    w.line("")
  }

  private def writeMockOperation(
    w: TsWriter,
    model: Model,
    op: OperationShape,
    service: ServiceShape,
    paths: PathResolver,
  ): Unit = {
    val opName = op.getId.getName
    val methodName = lowerFirst(opName)
    val http = op
      .getTrait(classOf[HttpTrait])
      .toScala
      .getOrElse(
        sys.error(s"operation ${op.getId} has no @http trait — cannot emit mock operation")
      )
    val input = model.expectShape(op.getInputShape, classOf[StructureShape])
    val output = model.expectShape(op.getOutputShape, classOf[StructureShape])

    val inputMembers = input.getAllMembers.asScala.toList
    val labelMembers = inputMembers.filter(_._2.hasTrait(classOf[HttpLabelTrait]))
    val queryMembers = inputMembers.filter(_._2.hasTrait(classOf[HttpQueryTrait]))
    val payloadMembers = inputMembers.filter(_._2.hasTrait(classOf[HttpPayloadTrait]))
    val headerMembers = inputMembers.filter(_._2.hasTrait(classOf[HttpHeaderTrait]))
    val payloadMember = payloadMembers.headOption
    val _ = headerMembers
    val bodyMembers = WireCodec.requestBodyMembers(input).map(m => m.getMemberName -> m)
    val isUnitInput = input.getId == UnitId
    val inputTypeName = w.tsName(input)

    val streams = opStreams(w, model, op)
    val opLabel = jsString(s"${op.getId.getName}")

    w.block("{", "},") {
      w.line(s"key: ${jsString(methodName)},")
      w.line(s"method: ${jsString(http.getMethod)},")
      w.line(
        "segments: $L,",
        segmentsArrayExpr(paths.resolve(service, op, http.getUri, labelMembers.map(_._1).toSet)),
      )
      writeMockDecodeInput(
        w,
        model,
        isUnitInput,
        inputTypeName,
        labelMembers,
        queryMembers,
        payloadMember,
        bodyMembers,
        streams.input,
        opLabel,
      )
      writeMockEncodeBody(w, model, output, streams.output)
      streams.output.foreach { out =>
        w.line(s"responseStreamEncoding: ${jsString(out.framing.encoding)},")
        out
          .framing
          .terminator
          .foreach(t => w.line(s"responseStreamTerminator: ${jsString(t)},"))
        w.line(
          s"encodeStream: (output) => ${streamEncodeExpr(out, s"(output as Record<string, unknown>)[${jsString(out.memberName)}] as ${out.streamType}")},"
        )
      }
    }
  }

  /** Emits `decodeInput: (req) => ...` building the operation's typed input from the matched
    * request. `@httpLabel`s come from `pathParams`, `@httpQuery` from `query`, and everything else
    * from the JSON body (either the whole body for an `@httpPayload` member, or the un-bound
    * structure members). The generated input schema then parses/brands the assembled object.
    */
  private def writeMockDecodeInput(
    w: TsWriter,
    model: Model,
    isUnitInput: Boolean,
    inputTypeName: String,
    labelMembers: List[(String, MemberShape)],
    queryMembers: List[(String, MemberShape)],
    payloadMember: Option[(String, MemberShape)],
    bodyMembers: List[(String, MemberShape)],
    inputStream: Option[StreamInfo],
    opLabel: String,
  ): Unit = {
    if (isUnitInput) {
      w.line("decodeInput: () => undefined,")
      return
    }
    w.block("decodeInput: (req) => {", "},") {
      w.line("const raw: Record<string, unknown> = {}")
      labelMembers.foreach { case (memberName, member) =>
        w.lit(
          s"raw[${jsString(memberName)}] = ${WireCodec.labelDecodeExpr(model, member, s"req.pathParams[${jsString(memberName)}]")}"
        )
      }
      queryMembers.foreach { case (memberName, member) =>
        val queryName = member.expectTrait(classOf[HttpQueryTrait]).getValue
        w.block(s"if (req.query[${jsString(queryName)}] !== undefined) {", "}") {
          w.lit(
            s"raw[${jsString(memberName)}] = ${WireCodec.queryDecodeExpr(model, member, s"(req.query[${jsString(queryName)}] as string)")}"
          )
        }
      }
      inputStream match {
        case Some(_) => ()
        case None    =>
          payloadMember match {
            case Some((memberName, member)) =>
              val target = model.expectShape(member.getTarget)
              w.lit(
                s"raw[${jsString(memberName)}] = req.body == null ? undefined : ${WireCodec.decodeExpr(w, model, target, "req.body")}"
              )
            case None if bodyMembers.nonEmpty =>
              w.line("const body = wireIsRecord(req.body) ? req.body : {}")
              bodyMembers.foreach { case (memberName, member) =>
                w.lit(
                  s"raw[${jsString(memberName)}] = ${WireCodec.decodeMemberExpr(w, model, member, s"body[${jsString(WireCodec.wireName(member))}]")}"
                )
              }
            case None => ()
          }
      }
      inputStream match {
        case None     => w.line(s"return ${inputTypeName}Schema.parse(raw) as $inputTypeName")
        case Some(in) =>
          // The streamed member is already absent from the generated schema
          // (see `writeStructure`), so parsing validates exactly the non-stream
          // members; the stream is attached afterwards, element-checked lazily
          // for ndjson.
          w.line(s"const decoded = ${inputTypeName}Schema.parse(raw)")
          w.block("return {", "}") {
            w.line("...decoded,")
            w.line(
              s"${in.memberName}: ${streamDecodeExpr(in, s"(req.stream ?? emptyStream())", opLabel)},"
            )
          }
      }
    }
  }

  /** Emits `encodeBody: (output) => ...` — the inverse of the client's response parse. When the
    * output has an `@httpPayload` member the body is just that member; otherwise the whole
    * (member-wrapped) structure is the body, minus any HTTP-bound (non-body) members such as
    * `@httpHeader` / `@httpResponseCode`, which don't belong in the JSON body. A Unit output
    * encodes to an empty body.
    */
  private def writeMockEncodeBody(
    w: TsWriter,
    model: Model,
    output: StructureShape,
    outputStream: Option[StreamInfo],
  ): Unit = {
    if (output.getId == UnitId) {
      w.line("encodeBody: () => ({}),")
      return
    }
    // The stream carries the body; anything else on the output travels in
    // headers / status, so there is no JSON body left to encode.
    if (outputStream.isDefined) {
      w.line("encodeBody: () => undefined,")
      return
    }
    WireCodec.bodyEncodeExpr(
      w,
      model,
      WireCodec.payloadMember(output),
      WireCodec.responseBodyMembers(output),
      "o",
    ) match {
      case None       => w.line("encodeBody: () => undefined,")
      case Some(body) =>
        w.lit(s"encodeBody: (output) => { const o = output as ${w.tsName(output)}; return $body },")
    }
  }

  /** Renders the operation's URI as a `MockUriSegment[]` array literal: literals become
    * `{ literal: '...' }`, `@httpLabel` captures `{ label: '...' }`.
    */
  private def segmentsArrayExpr(path: List[PathSegment]): String = {
    val segs = path.map {
      case PathSegment.Literal(value) => s"{ literal: ${jsString(value)} }"
      case PathSegment.Label(name)    => s"{ label: ${jsString(name)} }"
    }
    "[" + segs.mkString(", ") + "]"
  }

  // --------------------------------------------------------------------------
  // Misc
  // --------------------------------------------------------------------------

  /** RFC 3986 percent-encoding: everything but the unreserved characters, byte by byte. */
  private def percentEncode(s: String): String =
    s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      .map { b =>
        val c = (b & 0xff).toChar
        if (c.isLetterOrDigit && c < 128 || "-._~".contains(c))
          c.toString
        else
          f"%%${b & 0xff}%02X"
      }
      .mkString

  private def lowerFirst(s: String): String =
    if (s.isEmpty)
      s
    else
      s.head.toLower.toString + s.tail

  private def jsString(s: String): String =
    "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
