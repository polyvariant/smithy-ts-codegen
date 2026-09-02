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

package org.polyvariant.smithy.ts.api

import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ServiceShape

/** A hook into codegen decisions that depend on conventions the Smithy model does not itself
  * describe.
  *
  * The motivating case is a service mounted under a path prefix that is nowhere in the model: a
  * server framework that derives one from a trait, or a reverse proxy. The prefix cannot be plain
  * configuration, because one codegen run can emit several services and they need not share a
  * prefix — and it should not be a trait the generator knows, because that would mean baking one
  * organization's conventions into it. An extension inspects whatever the model does carry (traits,
  * the service `version`, the shape id) and decides.
  *
  * Implementations are discovered with `java.util.ServiceLoader`, so an implementation must be
  * public, have a no-argument constructor, and be listed in
  * `META-INF/services/org.polyvariant.smithy.ts.api.TsCodegenExtension` on the codegen's classpath:
  *
  * {{{
  * class InternalPrefix extends TsCodegenExtension {
  *   override def transformPath(
  *     service: ServiceShape,
  *     operation: OperationShape,
  *     path: List[PathSegment],
  *   ): List[PathSegment] =
  *     if (service.hasTrait(classOf[ApiInternalTrait]))
  *       PathSegment.Literal("internal") ::
  *         PathSegment.Literal(service.getVersion) ::
  *         path
  *     else
  *       path
  * }
  * }}}
  *
  * Every method has a default that changes nothing, so an implementation overrides only what it
  * cares about and stays source-compatible as methods are added.
  *
  * When several extensions are present they are applied in an unspecified order, each receiving the
  * previous one's result. Two extensions that both rewrite the same thing will therefore compose in
  * a way that depends on classpath order — put competing rules in one extension instead.
  */
trait TsCodegenExtension {

  /** Rewrite an operation's URI path.
    *
    * `path` is the operation's `@http` URI in structured form, and the return value replaces it, in
    * both the generated client and the generated Storybook mocks — so the two cannot drift apart.
    * The default returns it unchanged.
    *
    * Returning `Nil` means the root path (`/`).
    *
    * Only the path is rewritable here. Query parameters, headers and the method come from the
    * `@http` trait and the operation's members, which the model does describe.
    */
  // The default ignores everything but `path`; the names are still part of the
  // documented signature an implementor overrides, so they stay.
  @annotation.nowarn("msg=unused explicit parameter")
  def transformPath(
    service: ServiceShape,
    operation: OperationShape,
    path: List[PathSegment],
  ): List[PathSegment] = path

}
