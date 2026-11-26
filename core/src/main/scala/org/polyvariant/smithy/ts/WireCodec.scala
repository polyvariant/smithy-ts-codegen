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
import org.polyvariant.smithy.ts.TsCodegenPlugin.TsWriter
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.shapes.*
import software.amazon.smithy.model.traits.DefaultTrait
import software.amazon.smithy.model.traits.HttpHeaderTrait
import software.amazon.smithy.model.traits.HttpLabelTrait
import software.amazon.smithy.model.traits.HttpPayloadTrait
import software.amazon.smithy.model.traits.HttpPrefixHeadersTrait
import software.amazon.smithy.model.traits.HttpQueryParamsTrait
import software.amazon.smithy.model.traits.HttpQueryTrait
import software.amazon.smithy.model.traits.HttpResponseCodeTrait
import software.amazon.smithy.model.traits.JsonNameTrait
import software.amazon.smithy.model.traits.SparseTrait
import software.amazon.smithy.model.traits.StreamingTrait
import software.amazon.smithy.model.traits.TimestampFormatTrait

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** The wire encoding of `simpleRestJson`, as the generated code applies it: what a TS value looks
  * like in a JSON body, a header, a query parameter or a path label, and back.
  *
  * The zod schemas describe the *TS* value (a timestamp is a `Date`, a member is keyed by its
  * member name); the wire differs from that in ways a schema cannot express in both directions — a
  * timestamp is epoch seconds in a body but an HTTP date in a header, `@jsonName` renames a key. So
  * every aggregate shape gets a pair of plain functions, `encodeX(value: X): unknown` and
  * `decodeX(wire: unknown): unknown`, and the client validates with `XSchema.parse` only after
  * decoding. They are function declarations, so they are hoisted, and a recursive shape needs
  * nothing special.
  *
  * The defaults follow smithy4s, which is the reference implementation of the protocol on the
  * server side: epoch seconds for timestamps in a body, HTTP dates in headers, RFC 3339 date-times
  * in query parameters and labels, all overridable with `@timestampFormat`.
  */
private[ts] object WireCodec {

  /** Model-derived text (a `@jsonName` may hold a `$`) and regexes go out verbatim, never through
    * the writer's `$`-format parser.
    */
  private def out(w: TsWriter, text: String): Unit = w.lit(text)

  private def block(w: TsWriter, open: String)(body: => Unit): Unit =
    w.block("$L", "}", open)(body)

  /** Where a value is written. Only timestamps care: each location has its own default format. */
  enum Location {
    case Body, Header, Query, Label
  }

  // --------------------------------------------------------------------------
  // Shared runtime helpers
  // --------------------------------------------------------------------------

  /** The helpers every codec leans on. Not exported: they are implementation details of the wire
    * format, and `wire`-prefixed so no shape-derived name can collide with them.
    */
  def writeHelpers(w: TsWriter): Unit = {
    out(w, "const wireIsRecord = (value: unknown): value is Record<string, unknown> =>")
    out(w, "  typeof value === 'object' && value !== null && !Array.isArray(value)")
    out(w, "")
    out(w, "const wireEpochSeconds = (date: Date): number => date.getTime() / 1000")
    out(w, "")
    out(w, "/** RFC 3339, with the fraction only when there is one (`2014-04-29T18:30:38Z`). */")
    out(w, "const wireDateTime = (date: Date): string => date.toISOString().replace('.000Z', 'Z')")
    out(w, "")
    out(w, "/** IMF-fixdate (`Tue, 29 Apr 2014 18:30:38 GMT`). */")
    out(w, "const wireHttpDate = (date: Date): string => date.toUTCString()")
    out(w, "")
    out(w, "/** Any of the three formats: epoch seconds as a number or a numeric string")
    out(w, " * (as a header carries it), otherwise a date-time or an HTTP date. A value")
    out(w, " * that is neither is passed on for the schema to reject. */")
    block(w, "const wireTimestamp = (value: unknown): unknown => {") {
      out(w, "if (typeof value === 'number') return new Date(value * 1000)")
      out(w, "if (typeof value !== 'string') return value")
      out(
        w,
        "return /^-?\\d+(\\.\\d+)?$/.test(value) ? new Date(Number(value) * 1000) : new Date(value)",
      )
    }
    out(w, "")
    out(w, "/** Percent-encodes everything outside RFC 3986's unreserved set —")
    out(w, " * `encodeURIComponent` leaves `!'()*` alone. */")
    out(w, "const wireEncodeLabel = (value: string): string =>")
    out(
      w,
      "  encodeURIComponent(value).replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase())",
    )
    out(w, "")
    out(w, "/** A greedy label (`{key+}`) spans segments: its slashes stay slashes. */")
    out(w, "const wireEncodeGreedyLabel = (value: string): string =>")
    out(w, "  value.split('/').map(wireEncodeLabel).join('/')")
    out(w, "")
    out(w, "/** A string list item in a header, quoted when it holds a comma or a quote. */")
    out(w, "const wireQuoteHeaderItem = (value: string): string =>")
    out(
      w,
      "  /[,\"]/.test(value) ? '\"' + value.replace(/[\"\\\\]/g, (c) => '\\\\' + c) + '\"' : value",
    )
    out(w, "")
    out(w, "/** Splits a comma-separated header list, honouring quoted items. An HTTP")
    out(w, " * date contains a comma itself, so a list of them splits after each `GMT`. */")
    block(w, "const wireSplitHeader = (value: string, httpDates: boolean): string[] => {") {
      out(w, "if (httpDates) return value.split(/(?<=GMT)\\s*,\\s*/)")
      out(w, "const items: string[] = []")
      out(w, "const pattern = /\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^,]*?))\\s*(?:,|$)/gy")
      out(w, "let match: RegExpExecArray | null")
      block(
        w,
        "while (pattern.lastIndex < value.length && (match = pattern.exec(value)) !== null) {",
      ) {
        out(
          w,
          "items.push(match[1] !== undefined ? match[1].replace(/\\\\(.)/g, '$1') : (match[2] ?? ''))",
        )
      }
      out(w, "return items")
    }
    out(w, "")
    out(w, "/** The headers starting with `prefix`, keyed by the rest of the name, or")
    out(w, " * `undefined` when there are none. */")
    out(w, "const wirePrefixHeaders = (")
    out(w, "  headers: Record<string, string>,")
    out(w, "  prefix: string,")
    block(w, "): Record<string, string> | undefined => {") {
      out(w, "const out: Record<string, string> = {}")
      block(w, "for (const [name, value] of Object.entries(headers)) {") {
        out(w, "if (name.startsWith(prefix)) out[name.slice(prefix.length)] = value")
      }
      out(w, "return Object.keys(out).length === 0 ? undefined : out")
    }
    out(w, "")
  }

  // --------------------------------------------------------------------------
  // Per-shape codecs
  // --------------------------------------------------------------------------

  /** Whether a shape gets its own `encodeX` / `decodeX`. Structures and unions always do — their
    * members can be renamed, defaulted or null; a list or map only when its elements need
    * converting. Everything else is the same on the wire as in TS, apart from timestamps, which are
    * converted inline.
    */
  def hasCodec(model: Model, shape: Shape): Boolean =
    shape match {
      case _: StructureShape => true
      case _: UnionShape     => true
      case l: ListShape => elementNeedsConversion(model, model.expectShape(l.getMember.getTarget))
      case m: MapShape  => elementNeedsConversion(model, model.expectShape(m.getValue.getTarget))
      case _            => false
    }

  private def elementNeedsConversion(model: Model, target: Shape): Boolean =
    target.isTimestampShape || hasCodec(model, target)

  private def isLossless(member: MemberShape): Boolean =
    member.hasTrait(TsCodegenPlugin.LosslessTraitId)

  private def isNullable(member: MemberShape): Boolean = member.hasTrait(classOf[NullableTrait])

  /** The key a member is written under in a JSON object: `@jsonName`, else the member name. */
  def wireName(member: MemberShape): String =
    member.getTrait(classOf[JsonNameTrait]).toScala.map(_.getValue).getOrElse(member.getMemberName)

  def timestampFormat(
    member: Option[MemberShape],
    target: Shape,
    location: Location,
  ): TimestampFormatTrait.Format =
    member
      .flatMap(_.getTrait(classOf[TimestampFormatTrait]).toScala)
      .orElse(target.getTrait(classOf[TimestampFormatTrait]).toScala)
      .map(_.getFormat)
      .filter(_ != TimestampFormatTrait.Format.UNKNOWN)
      .getOrElse {
        location match {
          case Location.Body                   => TimestampFormatTrait.Format.EPOCH_SECONDS
          case Location.Header                 => TimestampFormatTrait.Format.HTTP_DATE
          case Location.Query | Location.Label => TimestampFormatTrait.Format.DATE_TIME
        }
      }

  private def encodeTimestamp(format: TimestampFormatTrait.Format, expr: String): String =
    format match {
      case TimestampFormatTrait.Format.HTTP_DATE => s"wireHttpDate($expr)"
      case TimestampFormatTrait.Format.DATE_TIME => s"wireDateTime($expr)"
      case _                                     => s"wireEpochSeconds($expr)"
    }

  /** TS expression turning a (non-null) TS value into its JSON-body value. */
  def encodeExpr(
    w: TsWriter,
    model: Model,
    member: Option[MemberShape],
    target: Shape,
    expr: String,
  ): String =
    if (member.exists(isLossless))
      // Typed `number | string`; a `bigint` is what the lossless serializer writes as a bare
      // numeric literal at full range.
      s"BigInt($expr)"
    else
      target match {
        case _: TimestampShape =>
          encodeTimestamp(timestampFormat(member, target, Location.Body), expr)
        case s if hasCodec(model, s) => s"encode${w.tsName(s)}($expr)"
        case _                       => expr
      }

  /** TS expression turning a (non-null) JSON-body value into the TS value, before validation. */
  def decodeExpr(w: TsWriter, model: Model, target: Shape, expr: String): String =
    target match {
      case _: TimestampShape       => s"wireTimestamp($expr)"
      case s if hasCodec(model, s) => s"decode${w.tsName(s)}($expr)"
      case _                       => expr
    }

  /** A member's `@default`, as the TS value it decodes to; `None` when it has none. */
  private def defaultExpr(w: TsWriter, model: Model, member: MemberShape): Option[String] =
    member
      .getTrait(classOf[DefaultTrait])
      .toScala
      .map(_.toNode)
      .filterNot(_.isNullNode)
      .map(node => decodeExpr(w, model, model.expectShape(member.getTarget), Node.printJson(node)))

  /** `value[member]` encoded for a JSON object, or `undefined` (so `JSON.stringify` drops it) when
    * absent. `null` is dropped too unless the member is `@nullable`, where it is a value.
    */
  def encodeMemberExpr(w: TsWriter, model: Model, member: MemberShape, access: String): String = {
    val target = model.expectShape(member.getTarget)
    val encoded = encodeExpr(w, model, Some(member), target, access)
    if (isNullable(member))
      s"$access === undefined ? undefined : $access === null ? null : $encoded"
    else if (encoded == access)
      s"$access ?? undefined"
    else
      s"$access == null ? undefined : $encoded"
  }

  /** A member decoded from the JSON value at `raw`: absent or `null` becomes the member's default,
    * or `undefined` — the protocol treats an explicit `null` like an absent member — except for a
    * `@nullable` member, where `null` is kept.
    */
  def decodeMemberExpr(w: TsWriter, model: Model, member: MemberShape, raw: String): String = {
    val target = model.expectShape(member.getTarget)
    val decoded = decodeExpr(w, model, target, raw)
    val missing = defaultExpr(w, model, member).getOrElse("undefined")
    if (isNullable(member))
      s"$raw === null ? null : $raw === undefined ? $missing : $decoded"
    else if (decoded == raw && missing == "undefined")
      s"$raw ?? undefined"
    else
      s"$raw == null ? $missing : $decoded"
  }

  /** Emits `encodeX` / `decodeX` for `shape`, if it has a codec. */
  def writeCodec(w: TsWriter, model: Model, shape: Shape): Unit =
    if (hasCodec(model, shape))
      shape match {
        case s: StructureShape => writeStructureCodec(w, model, s)
        case u: UnionShape     => writeUnionCodec(w, model, u)
        case l: ListShape      => writeListCodec(w, model, l)
        case m: MapShape       => writeMapCodec(w, model, m)
        case _                 => ()
      }

  /** Members that are part of a structure's JSON object: all of them, minus a stream (which is the
    * body itself and is never encoded here).
    */
  private def objectMembers(model: Model, shape: StructureShape): List[MemberShape] =
    shape.getAllMembers.asScala.values.toList.filterNot { m =>
      m.hasTrait(classOf[StreamingTrait]) ||
      model.expectShape(m.getTarget).hasTrait(classOf[StreamingTrait])
    }

  private def writeStructureCodec(w: TsWriter, model: Model, shape: StructureShape): Unit = {
    val name = w.tsName(shape)
    val members = objectMembers(model, shape)
    out(w, s"/** `$name` as it is written in a JSON body. */")
    val param =
      if (members.isEmpty)
        "_value"
      else
        "value"
    block(w, s"export function encode$name($param: $name): Record<string, unknown> {") {
      out(
        w,
        objectLiteral(
          members.map(m =>
            wireName(m) -> encodeMemberExpr(w, model, m, s"value.${m.getMemberName}")
          )
        ),
      )
    }
    out(w, "")
    out(w, s"/** `$name` read from a JSON body; validate the result with `${name}Schema`. */")
    block(w, s"export function decode$name(value: unknown): unknown {") {
      out(w, "if (!wireIsRecord(value)) return value")
      out(
        w,
        objectLiteral(
          members.map(m =>
            m.getMemberName -> decodeMemberExpr(w, model, m, s"value[${jsString(wireName(m))}]")
          )
        ),
      )
    }
    out(w, "")
  }

  /** `return { key: expr, ... }` — keys quoted, since a `@jsonName` need not be an identifier. */
  private def objectLiteral(entries: List[(String, String)]): String =
    if (entries.isEmpty)
      "return {}"
    else
      entries.map { case (k, v) => s"${jsString(k)}: $v" }.mkString("return { ", ", ", " }")

  private def writeUnionCodec(w: TsWriter, model: Model, shape: UnionShape): Unit = {
    val name = w.tsName(shape)
    val known = shape
      .getAllMembers
      .asScala
      .values
      .toList
      .filterNot(_.hasTrait(classOf[JsonUnknownTrait]))
    val discriminator = shape.getTrait(classOf[DiscriminatedUnionTrait]).toScala.map(_.getValue)
    out(w, s"/** `$name` as it is written in a JSON body. */")
    block(w, s"export function encode$name(value: $name): unknown {") {
      out(w, "const fields = value as Record<string, unknown>")
      discriminator match {
        case Some(field) =>
          known.foreach { m =>
            val target = w.tsName(model.expectShape(m.getTarget))
            out(
              w,
              s"if (fields[${jsString(field)}] === ${jsString(m.getMemberName)}) return { ...encode$target(value as unknown as $target), ${jsString(field)}: ${jsString(wireName(m))} }",
            )
          }
        case None =>
          known.foreach { m =>
            val target = model.expectShape(m.getTarget)
            val access = s"fields[${jsString(m.getMemberName)}]"
            out(
              w,
              s"if ($access !== undefined) return { ${jsString(wireName(m))}: ${encodeCast(w, model, m, target, access)} }",
            )
          }
      }
      // An open union's unknown variant is already in its wire form.
      out(w, "return value")
    }
    out(w, "")
    out(w, s"/** `$name` read from a JSON body; validate the result with `${name}Schema`. */")
    block(w, s"export function decode$name(value: unknown): unknown {") {
      out(w, "if (!wireIsRecord(value)) return value")
      discriminator match {
        case Some(field) =>
          known.foreach { m =>
            val target = w.tsName(model.expectShape(m.getTarget))
            out(
              w,
              s"if (value[${jsString(field)}] === ${jsString(wireName(m))}) return { ...(decode$target(value) as Record<string, unknown>), ${jsString(field)}: ${jsString(m.getMemberName)} }",
            )
          }
        case None =>
          known.foreach { m =>
            val target = model.expectShape(m.getTarget)
            val access = s"value[${jsString(wireName(m))}]"
            out(
              w,
              s"if ($access !== undefined && $access !== null) return { ${jsString(m.getMemberName)}: ${decodeExpr(w, model, target, access)} }",
            )
          }
      }
      out(w, "return value")
    }
    out(w, "")
  }

  private def writeListCodec(w: TsWriter, model: Model, shape: ListShape): Unit = {
    val name = w.tsName(shape)
    val member = shape.getMember
    val target = model.expectShape(member.getTarget)
    val sparse = shape.hasTrait(classOf[SparseTrait])
    val enc = encodeExpr(w, model, Some(member), target, "e")
    block(w, s"export function encode$name(value: $name): unknown[] {") {
      if (sparse)
        out(w, s"return value.map((e) => (e === null ? null : $enc))")
      else
        out(w, s"return value.map((e) => $enc)")
    }
    out(w, "")
    block(w, s"export function decode$name(value: unknown): unknown {") {
      out(
        w,
        s"return Array.isArray(value) ? value.map((e: unknown) => (e === null ? null : ${decodeExpr(w, model, target, "e")})) : value",
      )
    }
    out(w, "")
  }

  private def writeMapCodec(w: TsWriter, model: Model, shape: MapShape): Unit = {
    val name = w.tsName(shape)
    val member = shape.getValue
    val target = model.expectShape(member.getTarget)
    val sparse = shape.hasTrait(classOf[SparseTrait])
    val enc = encodeExpr(w, model, Some(member), target, "e")
    val encEntry =
      if (sparse)
        s"(e === null ? null : $enc)"
      else
        enc
    block(w, s"export function encode$name(value: $name): Record<string, unknown> {") {
      out(w, s"return Object.fromEntries(Object.entries(value).map(([k, e]) => [k, $encEntry]))")
    }
    out(w, "")
    block(w, s"export function decode$name(value: unknown): unknown {") {
      out(w, "if (!wireIsRecord(value)) return value")
      out(
        w,
        s"return Object.fromEntries(Object.entries(value).map(([k, e]) => [k, e === null ? null : ${decodeExpr(w, model, target, "e")}]))",
      )
    }
    out(w, "")
  }

  /** [[encodeExpr]] over an untyped `access`, cast to the type the conversion takes when there is
    * one.
    */
  private def encodeCast(
    w: TsWriter,
    model: Model,
    member: MemberShape,
    target: Shape,
    access: String,
  ): String =
    if (encodeExpr(w, model, Some(member), target, access) == access)
      access
    else {
      val ty =
        if (isLossless(member))
          "number | string"
        else if (target.isTimestampShape)
          "Date"
        else
          w.tsName(target)
      encodeExpr(w, model, Some(member), target, s"($access as $ty)")
    }

  // --------------------------------------------------------------------------
  // HTTP bindings
  // --------------------------------------------------------------------------

  /** A single (non-list) value as a string, for a header, query parameter or label. */
  private def scalarToString(
    member: Option[MemberShape],
    target: Shape,
    expr: String,
    location: Location,
  ): String =
    target match {
      case _: TimestampShape =>
        timestampFormat(member, target, location) match {
          case TimestampFormatTrait.Format.HTTP_DATE => s"wireHttpDate($expr)"
          case TimestampFormatTrait.Format.DATE_TIME => s"wireDateTime($expr)"
          case _                                     => s"String(wireEpochSeconds($expr))"
        }
      case _ => s"String($expr)"
    }

  /** A single (non-list) value read back from a header, query parameter or label string. */
  private def scalarFromString(member: Option[MemberShape], target: Shape, expr: String): String =
    if (member.exists(isLossless))
      // `Number(...)` would round: keep the string, which the `number | string` schema accepts.
      expr
    else
      target match {
        case _: ByteShape | _: ShortShape | _: IntegerShape | _: LongShape | _: BigIntegerShape |
            _: FloatShape | _: DoubleShape | _: BigDecimalShape =>
          s"Number($expr)"
        case _: BooleanShape   => s"($expr === 'true')"
        case _: TimestampShape => s"wireTimestamp($expr)"
        case _                 => expr
      }

  private def listElement(model: Model, target: Shape): Option[(MemberShape, Shape)] =
    target match {
      case l: ListShape => Some(l.getMember -> model.expectShape(l.getMember.getTarget))
      case _            => None
    }

  /** The header value for a (defined) member: a list is comma-joined. */
  def headerValueExpr(model: Model, member: MemberShape, expr: String): String = {
    val target = model.expectShape(member.getTarget)
    listElement(model, target) match {
      case Some((_, elem)) =>
        val item = scalarToString(Some(member), elem, "e", Location.Header)
        val quoted =
          if (elem.isStringShape)
            s"wireQuoteHeaderItem($item)"
          else
            item
        s"$expr.map((e) => $quoted).join(', ')"
      case None => scalarToString(Some(member), target, expr, Location.Header)
    }
  }

  /** A member read back from a (present) header value. */
  def headerDecodeExpr(model: Model, member: MemberShape, expr: String): String = {
    val target = model.expectShape(member.getTarget)
    listElement(model, target) match {
      case Some((_, elem)) =>
        val httpDates =
          elem.isTimestampShape &&
            timestampFormat(Some(member), elem, Location.Header) == TimestampFormatTrait
              .Format
              .HTTP_DATE
        s"wireSplitHeader($expr, $httpDates).map((e) => ${scalarFromString(None, elem, "e")})"
      case None => scalarFromString(Some(member), target, expr)
    }
  }

  /** The query value for a (defined) member: a list becomes repeated parameters. */
  def queryValueExpr(model: Model, member: MemberShape, expr: String): String = {
    val target = model.expectShape(member.getTarget)
    listElement(model, target) match {
      case Some((_, elem)) =>
        s"$expr.map((e) => ${scalarToString(Some(member), elem, "e", Location.Query)})"
      case None => scalarToString(Some(member), target, expr, Location.Query)
    }
  }

  /** A member read back from a (present) query value. */
  def queryDecodeExpr(model: Model, member: MemberShape, expr: String): String =
    scalarFromString(Some(member), model.expectShape(member.getTarget), expr)

  /** A label, rendered and percent-encoded for the path. */
  def labelExpr(model: Model, member: MemberShape, expr: String, greedy: Boolean): String = {
    val value = scalarToString(
      Some(member),
      model.expectShape(member.getTarget),
      expr,
      Location.Label,
    )
    if (greedy)
      s"wireEncodeGreedyLabel($value)"
    else
      s"wireEncodeLabel($value)"
  }

  /** A label read back from its (decoded) path segment. */
  def labelDecodeExpr(model: Model, member: MemberShape, expr: String): String =
    scalarFromString(Some(member), model.expectShape(member.getTarget), expr)

  /** Members of a request (input) structure that travel in its JSON body. */
  def requestBodyMembers(shape: StructureShape): List[MemberShape] =
    shape.getAllMembers.asScala.values.toList.filterNot { m =>
      m.hasTrait(classOf[HttpLabelTrait]) || m.hasTrait(classOf[HttpQueryTrait]) ||
      m.hasTrait(classOf[HttpQueryParamsTrait]) || m.hasTrait(classOf[HttpHeaderTrait]) ||
      m.hasTrait(classOf[HttpPrefixHeadersTrait]) || m.hasTrait(classOf[HttpPayloadTrait])
    }

  /** Members of a response (output or error) structure that travel in its JSON body. `@httpLabel`
    * and `@httpQuery` only bind input members, so on a response they are ordinary body members.
    */
  def responseBodyMembers(shape: StructureShape): List[MemberShape] =
    shape.getAllMembers.asScala.values.toList.filterNot { m =>
      m.hasTrait(classOf[HttpHeaderTrait]) || m.hasTrait(classOf[HttpPrefixHeadersTrait]) ||
      m.hasTrait(classOf[HttpResponseCodeTrait]) || m.hasTrait(classOf[HttpPayloadTrait])
    }

  def payloadMember(shape: StructureShape): Option[MemberShape] =
    shape.getAllMembers.asScala.values.find(_.hasTrait(classOf[HttpPayloadTrait]))

  /** The JSON body for a request or response: the `@httpPayload` member on its own, or an object of
    * the body members. `None` when nothing goes in the body.
    */
  def bodyEncodeExpr(
    w: TsWriter,
    model: Model,
    payload: Option[MemberShape],
    bodyMembers: List[MemberShape],
    value: String,
  ): Option[String] =
    payload match {
      case Some(m) =>
        val access = s"$value.${m.getMemberName}"
        Some(
          s"$access === undefined ? undefined : ${encodeExpr(w, model, Some(m), model.expectShape(m.getTarget), access)}"
        )
      case None if bodyMembers.isEmpty => None
      case None                        =>
        val fields = bodyMembers.map { m =>
          s"${jsString(wireName(m))}: ${encodeMemberExpr(w, model, m, s"$value.${m.getMemberName}")}"
        }
        Some(fields.mkString("{ ", ", ", " }"))
    }

  /** `[memberName, expr]` pairs reading a response structure's members from `res` (status,
    * lowercased headers, decoded JSON body), for an object literal that is then validated.
    */
  def responseMemberExprs(w: TsWriter, model: Model, shape: StructureShape)
    : List[(String, String)] =
    objectMembers(model, shape).map { m =>
      val name = m.getMemberName
      val target = model.expectShape(m.getTarget)
      val missing = defaultExpr(w, model, m).getOrElse("undefined")
      if (m.hasTrait(classOf[HttpResponseCodeTrait]))
        name -> "res.status"
      else if (m.hasTrait(classOf[HttpHeaderTrait])) {
        val header =
          s"res.headers[${jsString(m.expectTrait(classOf[HttpHeaderTrait]).getValue.toLowerCase)}]"
        name -> s"$header === undefined ? $missing : ${headerDecodeExpr(model, m, header)}"
      } else if (m.hasTrait(classOf[HttpPrefixHeadersTrait])) {
        val prefix = m.expectTrait(classOf[HttpPrefixHeadersTrait]).getValue.toLowerCase
        name -> s"wirePrefixHeaders(res.headers, ${jsString(prefix)})"
      } else if (m.hasTrait(classOf[HttpPayloadTrait]))
        name -> s"res.body == null ? $missing : ${decodeExpr(w, model, target, "res.body")}"
      else
        name -> decodeMemberExpr(w, model, m, s"body[${jsString(wireName(m))}]")
    }

  /** Emits `decodeXResponse(res)`: an output or error structure read from a whole response — body,
    * headers and status — and validated.
    */
  def writeResponseDecoder(w: TsWriter, model: Model, shape: StructureShape): Unit = {
    val name = w.tsName(shape)
    val exprs = responseMemberExprs(w, model, shape)
    val usesBody = exprs.exists(_._2.contains("body["))
    out(w, s"/** `$name` read from a response: its body, headers and status. */")
    block(
      w,
      s"export function decode${name}Response(res: { status: number; headers: Record<string, string>; body?: unknown }): $name {",
    ) {
      if (usesBody)
        out(w, "const body = wireIsRecord(res.body) ? res.body : {}")
      out(
        w,
        s"return ${name}Schema.parse(${exprs.map { case (k, v) => s"${jsString(k)}: $v" }.mkString("{ ", ", ", " }")}) as $name",
      )
    }
    out(w, "")
  }

  private def jsString(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

}
