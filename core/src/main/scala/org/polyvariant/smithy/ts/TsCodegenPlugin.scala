package org.polyvariant.smithy.ts

import alloy.NullableTrait
import software.amazon.smithy.build.PluginContext
import software.amazon.smithy.build.SmithyBuildPlugin
import software.amazon.smithy.codegen.core.ImportContainer
import software.amazon.smithy.codegen.core.Symbol
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
import software.amazon.smithy.model.traits.HttpQueryTrait
import software.amazon.smithy.model.traits.HttpResponseCodeTrait
import software.amazon.smithy.model.traits.HttpTrait
import software.amazon.smithy.model.traits.MixinTrait
import software.amazon.smithy.model.traits.RequiredTrait
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
  *   3. a small `Transport` interface;
  *   4. one client class per `@simpleRestJson` service, with one method per operation. The method
  *      walks the operation's `@http` trait to build the request and parses the response with the
  *      generated output schema.
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

  /** Single-file output, so we never import anything. */
  private final class TsImports extends ImportContainer {
    override def importSymbol(symbol: Symbol, alias: String): Unit = ()
  }

  /** SymbolWriter with TS placeholders: $S — JS-quoted single-quoted string (escaping)
    *
    * `block(open, close)(body)` wraps `openBlock` so Scala 3 doesn't resolve the underlying Java
    * varargs overload and treat `close` as a format arg.
    */
  private final class TsWriter extends SymbolWriter[TsWriter, TsImports](new TsImports) {
    putFormatter('S', StringFormatter)
    trimTrailingSpaces()
    setIndentText("  ")

    def line(content: String, args: Object*): Unit = {
      val _ = write(content, args*)
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

  private object StringFormatter extends BiFunction[Object, String, String] {

    def apply(value: Object, indent: String): String = {
      val _ = indent
      val s = value.toString
      "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
    }

  }

  def generate(model: Model, excludeServices: Set[String]): String = {
    val emittables = model.shapes.iterator.asScala.filter(emittable).toList
    val ordered = topoSort(emittables)
    val w = new TsWriter

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

    val servicesToEmit = model
      .getServiceShapes
      .asScala
      .toList
      .filterNot(s => excludeServices.contains(s.getId.toString))
      .sortBy(_.getId.toString)
    if (servicesToEmit.nonEmpty) {
      w.line("")
      w.line("// --- Transport ---")
      w.line("")
      writeTransport(w)
      w.line("")
      w.line("// --- Service clients ---")
      w.line("")
      servicesToEmit.foreach(svc => writeClient(w, model, svc))
      w.line("")
      w.line("// --- Storybook mock server ---")
      w.line("")
      writeMockRuntime(w)
      servicesToEmit.foreach(svc => writeMockService(w, model, svc))
    }

    w.toString
  }

  // --------------------------------------------------------------------------
  // Shape selection & ordering
  // --------------------------------------------------------------------------

  private def topoSort(shapes: List[Shape]): List[Shape] = {
    val emittableSet = shapes.map(_.getId).toSet
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
          .toSet
          .asJava
      graph.addDependencies(shape.getId, deps)
    }
    val cycles = graph.findCycles.asScala.toList
    if (cycles.nonEmpty) {
      val rendered = cycles.map(_.asScala.map(_.toString).mkString(" -> ")).mkString("; ")
      sys.error(s"ts-codegen does not support recursive shapes. Cycles: $rendered")
    }
    val byId = shapes.map(s => s.getId -> s).toMap
    graph.toSortedList.asScala.iterator.flatMap(byId.get).toList
  }

  private def emittable(shape: Shape): Boolean = {
    val id = shape.getId
    if (id.getNamespace == "smithy.api")
      false
    else if (id.getNamespace.startsWith("smithy4s"))
      false
    else if (id.getNamespace.startsWith("alloy"))
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
      t.getValue.split("\n").foreach(line => w.line(s" * $line"))
      w.line(" */")
    }

  private def writeStructure(w: TsWriter, model: Model, shape: StructureShape): Unit = {
    val name = shape.getId.getName
    w.block(s"export const ${name}Schema = z.object({", "})") {
      shape.getAllMembers.asScala.foreach { case (memberName, member) =>
        member.getTrait(classOf[DocumentationTrait]).toScala.foreach { t =>
          w.line(s"/** ${t.getValue.replace("\n", " ").trim} */")
        }
        val target = model.expectShape(member.getTarget)
        val schemaExpr = inlineSchemaExpr(target)
        val required = member.hasTrait(classOf[RequiredTrait])
        val nullable = member.hasTrait(classOf[NullableTrait])
        val finalExpr =
          if (nullable)
            schemaExpr + ".nullish()"
          else if (required)
            schemaExpr
          else
            schemaExpr + ".optional()"
        w.line(s"$memberName: $finalExpr,")
      }
    }
    w.line(s"export type $name = z.infer<typeof ${name}Schema>")
  }

  private def writeUnion(w: TsWriter, model: Model, shape: UnionShape): Unit = {
    val name = shape.getId.getName
    val members = shape.getAllMembers.asScala.toList
    if (members.isEmpty) {
      w.line(s"export const ${name}Schema = z.never()")
      w.line(s"export type $name = z.infer<typeof ${name}Schema>")
    } else {
      w.block(s"export const ${name}Schema = z.union([", "])") {
        members.foreach { case (memberName, member) =>
          val target = model.expectShape(member.getTarget)
          val variant = inlineSchemaExpr(target)
          w.line(s"z.object({ $memberName: $variant }),")
        }
      }
      w.line(s"export type $name = z.infer<typeof ${name}Schema>")
    }
  }

  private def writeEnum(w: TsWriter, shape: EnumShape): Unit = {
    val name = shape.getId.getName
    val values = shape.getEnumValues.asScala.values.toList
    val literals = values.map(jsString).mkString(", ")
    w.line(s"export const ${name}Schema = z.enum([$literals])")
    w.line(s"export type $name = z.infer<typeof ${name}Schema>")
  }

  private def writeList(w: TsWriter, model: Model, shape: ListShape): Unit = {
    val name = shape.getId.getName
    val target = model.expectShape(shape.getMember.getTarget)
    val elem = inlineSchemaExpr(target)
    w.line(s"export const ${name}Schema = z.array($elem)")
    w.line(s"export type $name = z.infer<typeof ${name}Schema>")
  }

  private def writeMap(w: TsWriter, model: Model, shape: MapShape): Unit = {
    val name = shape.getId.getName
    val value = model.expectShape(shape.getValue.getTarget)
    val valueExpr = inlineSchemaExpr(value)
    w.line(s"export const ${name}Schema = z.record(z.string(), $valueExpr)")
    w.line(s"export type $name = z.infer<typeof ${name}Schema>")
  }

  private def writeAlias(w: TsWriter, shape: Shape): Unit = {
    val name = shape.getId.getName
    val base = primitiveSchema(shape)
    val branded = s"$base.brand<'$name'>()"
    w.line(s"export const ${name}Schema = $branded")
    w.line(s"export type $name = z.infer<typeof ${name}Schema>")
  }

  private def inlineSchemaExpr(target: Shape): String = {
    val id = target.getId
    if (id.getNamespace == "smithy.api")
      primitiveSchema(target)
    else
      s"${id.getName}Schema"
  }

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
  // Errors
  // --------------------------------------------------------------------------

  private def writeErrorClass(w: TsWriter, shape: StructureShape): Unit = {
    val name = shape.getId.getName
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
      w.line("query?: Record<string, string | number | boolean | undefined>")
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

  // --------------------------------------------------------------------------
  // Service clients
  // --------------------------------------------------------------------------

  private def writeClient(w: TsWriter, model: Model, service: ServiceShape): Unit = {
    val svcName = service.getId.getName
    w.block(s"export class ${svcName}Client {", "}") {
      // Avoid parameter-property syntax — `tsc --erasableSyntaxOnly` rejects it.
      w.line("private readonly transport: Transport")
      w.block("constructor(transport: Transport) {", "}") {
        w.line("this.transport = transport")
      }
      val ops = service
        .getOperations
        .asScala
        .toList
        .map(model.expectShape(_, classOf[OperationShape]))
        .sortBy(_.getId.toString)
      ops.foreach(op => writeOperation(w, model, op, service))
    }
    w.line("")
  }

  private def writeOperation(w: TsWriter, model: Model, op: OperationShape, service: ServiceShape)
    : Unit = {
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

    val httpBoundMembers =
      (labelMembers ++ queryMembers ++ headerMembers ++ payloadMembers).map(_._1).toSet
    val bodyMembers = inputMembers.filterNot { case (n, _) => httpBoundMembers.contains(n) }

    val inputTypeName = input.getId.getName
    val isUnitInput = input.getId.toString == "smithy.api#Unit"

    w.line("")
    op.getTrait(classOf[DocumentationTrait]).toScala.foreach { t =>
      w.line(s"/** ${t.getValue.replace("\n", " ").trim} */")
    }
    val signature =
      if (isUnitInput)
        s"async $methodName(opts?: TransportOptions): Promise<${returnType(output)}> {"
      else
        s"async $methodName(input: $inputTypeName, opts?: TransportOptions): Promise<${returnType(output)}> {"

    w.block(signature, "}") {
      // urlExpression returns a TS template literal containing `${...}`; route
      // through the $L (literal) formatter so smithy's `$`-parser doesn't try
      // to interpret it.
      w.line("const url = $L", urlExpression(http.getUri, labelMembers.map(_._1).toSet))

      if (queryMembers.nonEmpty) {
        w.line("const query: Record<string, string | number | boolean | undefined> = {}")
        queryMembers.foreach { case (memberName, member) =>
          val queryName = member.expectTrait(classOf[HttpQueryTrait]).getValue
          w.line(
            s"if (input.$memberName !== undefined) query[${jsString(queryName)}] = input.$memberName as string | number | boolean"
          )
        }
      }
      if (headerMembers.nonEmpty) {
        w.line("const headers: Record<string, string> = {}")
        headerMembers.foreach { case (memberName, member) =>
          val headerName = member.expectTrait(classOf[HttpHeaderTrait]).getValue
          w.line(
            s"if (input.$memberName !== undefined) headers[${jsString(headerName)}] = String(input.$memberName)"
          )
        }
      }

      val bodyExpr: String =
        payloadMember match {
          case Some((memberName, _))       => s"input.$memberName"
          case None if bodyMembers.isEmpty => "undefined"
          case None                        =>
            val pairs = bodyMembers.map { case (n, _) => s"$n: input.$n" }.mkString(", ")
            s"{ $pairs }"
        }

      w.block("const res = await this.transport.request({", "})") {
        w.line(s"operation: ${jsString(s"${svcName}Client.$methodName")},")
        w.line(s"method: ${jsString(http.getMethod)},")
        w.line("url,")
        if (queryMembers.nonEmpty)
          w.line("query,")
        if (headerMembers.nonEmpty)
          w.line("headers,")
        w.line(s"body: $bodyExpr,")
        w.line("options: opts,")
      }

      // 2xx → parse the typed output and return. Non-2xx falls through to
      // the error dispatch below; we never run the output parser on an error
      // body (which is what produced the bogus Zod "expected: object" error
      // when an undeclared status came back). The transport already throws
      // `UnauthenticatedError` for 401 itself, so we won't see one here.
      w.block("if (res.status >= 200 && res.status < 300) {", "}") {
        writeResponseParse(w, model, output)
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
            val name = candidates.head.getId.getName
            w.line(s"throw new ${name}Error(${name}Schema.parse(res.body))")
          } else {
            w.line("const errorType = res.headers['x-error-type']")
            candidates.foreach { err =>
              val name = err.getId.getName
              w.line(
                s"if (errorType === ${jsString(name)}) throw new ${name}Error(${name}Schema.parse(res.body))"
              )
            }
          }
        }
      }

      val opLabel = jsString(s"${svcName}Client.$methodName")
      w.line(s"throw new UnexpectedResponseError($opLabel, res.status, res.body, res.headers)")
    }
  }

  private def urlExpression(
    uri: software.amazon.smithy.model.pattern.UriPattern,
    labelNames: Set[String],
  ): String = {
    val segments = uri.getSegments.asScala
    val rendered =
      segments
        .iterator
        .map { seg =>
          if (seg.isLiteral)
            "/" + seg.getContent
          else {
            val name = seg.getContent
            if (!labelNames.contains(name))
              sys.error(
                s"URI references label {$name} but no input member is bound with @httpLabel"
              )
            s"/$${encodeURIComponent(String(input.$name))}"
          }
        }
        .mkString
    val final_ =
      if (rendered.isEmpty)
        "/"
      else
        rendered
    "`" + final_ + "`"
  }

  /** Member names of `shape` that are bound to a non-body HTTP location (`@httpHeader`,
    * `@httpPrefixHeaders`, `@httpQuery`, `@httpLabel`, `@httpResponseCode`). These live outside the
    * JSON body, so they must be excluded when parsing/serializing a structure's body — otherwise a
    * required header/label/etc. member would be (wrongly) demanded of the body payload.
    * `@httpPayload` is deliberately not here: it *replaces* the body wholesale and is handled by
    * dedicated call-site logic.
    */
  private def httpBodyExcludedMembers(shape: StructureShape): Set[String] =
    shape
      .getAllMembers
      .asScala
      .collect {
        case (name, m)
            if m.hasTrait(classOf[HttpHeaderTrait]) ||
              m.hasTrait(classOf[HttpPrefixHeadersTrait]) ||
              m.hasTrait(classOf[HttpQueryTrait]) ||
              m.hasTrait(classOf[HttpLabelTrait]) ||
              m.hasTrait(classOf[HttpResponseCodeTrait]) =>
          name
      }
      .toSet

  /** A Zod expression for `shape`'s body: its full schema with any HTTP-bound (non-body) members
    * omitted.
    */
  private def bodySchemaExpr(shape: StructureShape): String = {
    val excluded = httpBodyExcludedMembers(shape)
    val base = s"${shape.getId.getName}Schema"
    if (excluded.isEmpty)
      base
    else {
      val keys = excluded.toList.sorted.map(n => s"${jsString(n)}: true").mkString(", ")
      s"$base.omit({ $keys })"
    }
  }

  /** Emits the `return ...` for a successful (2xx) response: parses the JSON body against the
    * output's body schema (HTTP-bound members omitted) and, when the output has non-body bindings,
    * re-hydrates those members from the response headers / status before validating the whole
    * object against the full output schema.
    */
  private def writeResponseParse(w: TsWriter, model: Model, output: StructureShape): Unit = {
    if (output.getId.toString == "smithy.api#Unit") {
      w.line("return undefined")
      return
    }
    val members = output.getAllMembers.asScala.toList
    val payload = members.find { case (_, m) => m.hasTrait(classOf[HttpPayloadTrait]) }
    val headerMembers = members.filter(_._2.hasTrait(classOf[HttpHeaderTrait]))
    val responseCodeMembers = members.filter(_._2.hasTrait(classOf[HttpResponseCodeTrait]))

    // Body expression: the whole body for an @httpPayload member, otherwise the
    // body schema (bound members omitted) parsed from the JSON body.
    val bodyParse: String =
      payload match {
        case Some((memberName, m)) =>
          val target = model.expectShape(m.getTarget)
          s"{ ${memberName}: ${inlineSchemaExpr(target)}.parse(res.body) }"
        case None => s"${bodySchemaExpr(output)}.parse(res.body)"
      }

    if (headerMembers.isEmpty && responseCodeMembers.isEmpty) {
      w.line(s"return $bodyParse")
      return
    }

    // Re-hydrate the non-body members from the response, then validate the full
    // shape against the complete output schema so the returned value is typed.
    w.line(s"const raw: Record<string, unknown> = { ...($bodyParse as Record<string, unknown>) }")
    headerMembers.foreach { case (memberName, member) =>
      val headerName = member.expectTrait(classOf[HttpHeaderTrait]).getValue
      val target = model.expectShape(member.getTarget)
      w.block(s"if (res.headers[${jsString(headerName.toLowerCase)}] !== undefined) {", "}") {
        w.line(
          s"raw[${jsString(memberName)}] = ${coerceFromString(target, s"res.headers[${jsString(headerName.toLowerCase)}]")}"
        )
      }
    }
    responseCodeMembers.foreach { case (memberName, _) =>
      w.line(s"raw[${jsString(memberName)}] = res.status")
    }
    w.line(s"return ${output.getId.getName}Schema.parse(raw) as ${output.getId.getName}")
  }

  private def returnType(output: StructureShape): String =
    if (output.getId.toString == "smithy.api#Unit")
      "void"
    else
      output.getId.getName

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

  private def writeMockRuntime(w: TsWriter): Unit = {
    w.line("/** A request as seen by the mock server: the URL path already has any")
    w.line(" * proxy prefix stripped, `pathParams` holds decoded `@httpLabel`")
    w.line(" * values, `query` the parsed query string, `body` the parsed JSON body. */")
    w.block("export interface MockRequest {", "}") {
      w.line("method: string")
      w.line("path: string")
      w.line("pathParams: Record<string, string>")
      w.line("query: Record<string, string | undefined>")
      w.line("body: unknown")
    }
    w.line("")
    w.block("export interface MockResponse {", "}") {
      w.line("status: number")
      w.line("body: unknown")
      w.line("headers: Record<string, string>")
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

  private def writeMockService(w: TsWriter, model: Model, service: ServiceShape): Unit = {
    val svcName = service.getId.getName
    val ops = service
      .getOperations
      .asScala
      .toList
      .map(model.expectShape(_, classOf[OperationShape]))
      .sortBy(_.getId.toString)

    // Handlers interface: one method per operation, decoded input -> typed
    // output (or a thrown error). Unit inputs take no argument.
    w.block(s"export interface ${svcName}Handlers {", "}") {
      ops.foreach { op =>
        val opName = op.getId.getName
        val methodName = lowerFirst(opName)
        val input = model.expectShape(op.getInputShape, classOf[StructureShape])
        val output = model.expectShape(op.getOutputShape, classOf[StructureShape])
        val isUnitInput = input.getId.toString == "smithy.api#Unit"
        val outTy = returnType(output)
        val ret =
          if (outTy == "void")
            "void | Promise<void>"
          else
            s"$outTy | Promise<$outTy>"
        op.getTrait(classOf[DocumentationTrait]).toScala.foreach { t =>
          w.line(s"/** ${t.getValue.replace("\n", " ").trim} */")
        }
        if (isUnitInput)
          w.line(s"$methodName(): $ret")
        else
          w.line(s"$methodName(input: ${input.getId.getName}): $ret")
      }
    }
    w.line("")

    w.block(s"export const ${svcName}Mock: MockServiceDescriptor<${svcName}Handlers> = {", "}") {
      w.line(s"serviceName: ${jsString(svcName)},")
      w.block("operations: [", "],") {
        ops.foreach(op => writeMockOperation(w, model, op))
      }
    }
    w.line("")
  }

  private def writeMockOperation(w: TsWriter, model: Model, op: OperationShape): Unit = {
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

    val httpBoundMembers =
      (labelMembers ++ queryMembers ++ headerMembers ++ payloadMembers).map(_._1).toSet
    val bodyMembers = inputMembers.filterNot { case (n, _) => httpBoundMembers.contains(n) }
    val isUnitInput = input.getId.toString == "smithy.api#Unit"
    val inputTypeName = input.getId.getName

    w.block("{", "},") {
      w.line(s"key: ${jsString(methodName)},")
      w.line(s"method: ${jsString(http.getMethod)},")
      w.line("segments: $L,", segmentsArrayExpr(http.getUri, labelMembers.map(_._1).toSet))
      writeMockDecodeInput(
        w,
        model,
        isUnitInput,
        inputTypeName,
        labelMembers,
        queryMembers,
        payloadMember,
        bodyMembers,
      )
      writeMockEncodeBody(w, output)
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
  ): Unit = {
    if (isUnitInput) {
      w.line("decodeInput: () => undefined,")
      return
    }
    w.block("decodeInput: (req) => {", "},") {
      w.line("const raw: Record<string, unknown> = {}")
      labelMembers.foreach { case (memberName, member) =>
        val target = model.expectShape(member.getTarget)
        w.line(
          s"raw[${jsString(memberName)}] = ${coerceFromString(target, s"req.pathParams[${jsString(memberName)}]")}"
        )
      }
      queryMembers.foreach { case (memberName, member) =>
        val queryName = member.expectTrait(classOf[HttpQueryTrait]).getValue
        val target = model.expectShape(member.getTarget)
        w.block(s"if (req.query[${jsString(queryName)}] !== undefined) {", "}") {
          w.line(
            s"raw[${jsString(memberName)}] = ${coerceFromString(target, s"req.query[${jsString(queryName)}] as string")}"
          )
        }
      }
      payloadMember match {
        case Some((memberName, _))        => w.line(s"raw[${jsString(memberName)}] = req.body")
        case None if bodyMembers.nonEmpty =>
          w.line("const body = (req.body ?? {}) as Record<string, unknown>")
          bodyMembers.foreach { case (memberName, _) =>
            w.line(s"raw[${jsString(memberName)}] = body[${jsString(memberName)}]")
          }
        case None => ()
      }
      w.line(s"return ${inputTypeName}Schema.parse(raw) as $inputTypeName")
    }
  }

  /** Emits `encodeBody: (output) => ...` — the inverse of the client's response parse. When the
    * output has an `@httpPayload` member the body is just that member; otherwise the whole
    * (member-wrapped) structure is the body, minus any HTTP-bound (non-body) members such as
    * `@httpHeader` / `@httpResponseCode`, which don't belong in the JSON body. A Unit output
    * encodes to an empty body.
    */
  private def writeMockEncodeBody(w: TsWriter, output: StructureShape): Unit = {
    if (output.getId.toString == "smithy.api#Unit") {
      w.line("encodeBody: () => ({}),")
      return
    }
    val payload = output.getAllMembers.asScala.find { case (_, m) =>
      m.hasTrait(classOf[HttpPayloadTrait])
    }
    payload match {
      case Some((memberName, _)) =>
        w.line(
          s"encodeBody: (output) => (output as Record<string, unknown>)[${jsString(memberName)}],"
        )
      case None =>
        val excluded = httpBodyExcludedMembers(output)
        if (excluded.isEmpty)
          w.line("encodeBody: (output) => output,")
        else {
          // Strip the non-body members out of the returned object.
          val destructured = excluded.toList.sorted.map(n => s"${n}: _$n").mkString(", ")
          w.line(
            s"encodeBody: (output) => { const { $destructured, ...body } = output as Record<string, unknown>; return body },"
          )
        }
    }
  }

  /** TS expression that coerces a raw string (from a path label or query param) into the value the
    * input schema expects before branding/parsing. Numbers and booleans are converted; everything
    * else is left as a string.
    */
  private def coerceFromString(target: Shape, expr: String): String =
    target match {
      case _: ByteShape | _: ShortShape | _: IntegerShape | _: LongShape | _: BigIntegerShape |
          _: FloatShape | _: DoubleShape | _: BigDecimalShape =>
        s"Number($expr)"
      case _: BooleanShape => s"($expr === 'true')"
      case _               => expr
    }

  /** Renders the operation's URI as a `MockUriSegment[]` array literal: literals become
    * `{ literal: '...' }`, `@httpLabel` captures `{ label: '...' }`.
    */
  private def segmentsArrayExpr(
    uri: software.amazon.smithy.model.pattern.UriPattern,
    labelNames: Set[String],
  ): String = {
    val segs = uri
      .getSegments
      .asScala
      .toList
      .map { seg =>
        if (seg.isLiteral)
          s"{ literal: ${jsString(seg.getContent)} }"
        else {
          val name = seg.getContent
          if (!labelNames.contains(name))
            sys.error(s"URI references label {$name} but no input member is bound with @httpLabel")
          s"{ label: ${jsString(name)} }"
        }
      }
    "[" + segs.mkString(", ") + "]"
  }

  // --------------------------------------------------------------------------
  // Misc
  // --------------------------------------------------------------------------

  private def lowerFirst(s: String): String =
    if (s.isEmpty)
      s
    else
      s.head.toLower.toString + s.tail

  private def jsString(s: String): String =
    "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
