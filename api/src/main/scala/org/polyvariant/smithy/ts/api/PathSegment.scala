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

/** One segment of an operation's URI path.
  *
  * This is the structured form of an `@http` URI: `/things/{id}/tags` is
  * `Literal("things") :: Label("id") :: Literal("tags") :: Nil`. Extensions see and return this
  * rather than a rendered string, because the two consumers of a path need to tell the two apart —
  * the client interpolates a label as `${encodeURIComponent(...)}`, while the Storybook mock router
  * matches it as a wildcard. A `String => String` hook would force both to re-parse the result, and
  * would let an extension hand back something that no longer parses at all.
  *
  * Neither case carries a leading or trailing slash; the generator joins them.
  */
sealed trait PathSegment extends Product with Serializable

object PathSegment {

  /** A fixed segment, matched verbatim. The value must not contain `/` — return several segments
    * rather than one containing a slash, or the mock router will never match it.
    */
  final case class Literal(value: String) extends PathSegment

  /** A capture, bound to the input member of the same name via `@httpLabel`.
    *
    * An extension may reorder or drop a label, but must not invent one: the name has to resolve to
    * a member actually bound with `@httpLabel`, or codegen fails with an error naming it.
    */
  final case class Label(name: String) extends PathSegment

}
