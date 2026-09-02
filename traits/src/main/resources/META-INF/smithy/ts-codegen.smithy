$version: "2"

namespace org.polyvariant.smithy.ts

/// Preserve this numeric member's exact value, even outside JavaScript's safe
/// integer range.
///
/// JavaScript numbers are IEEE-754 doubles, so integers outside ±(2^53 - 1)
/// cannot be represented exactly. `JSON.parse` rounds such a value before any
/// schema can see it, and there is no way to recover the original: a `long`,
/// `bigInteger` or `bigDecimal` whose values can exceed that range therefore
/// has no lossless `number` representation on the client.
///
/// Applying this trait types the member as `number | string`. Whether a given
/// value arrives as a `number` or a `string` is decided per value at runtime:
/// anything that fits exactly becomes a `number`, and only values that would
/// lose precision surface as their exact decimal string. On the way out, all of
/// `number`, `string` and `bigint` are accepted.
///
/// This requires a transport that serialises and parses JSON losslessly —
/// `@polyvariant/smithy-ts-runtime` does. A transport built on plain
/// `JSON.parse` / `JSON.stringify` cannot honor the trait: `JSON.parse` will
/// have rounded the value before the schema runs, and `JSON.stringify` quotes a
/// string rather than emitting a bare numeric literal.
///
/// The trait is deliberately member-scoped rather than shape-scoped: whether a
/// given field can exceed the safe range is a property of that field, and the
/// same numeric shape is often reused for values that stay well inside it.
/// Integral shapes only: the exact value is carried as a `bigint` on the way
/// out, and `BigInt` has no fractional representation. A `float`, `double` or
/// `bigDecimal` that needs the same treatment would need a different carrier,
/// so the selector deliberately excludes them rather than promising something
/// this cannot deliver.
@trait(selector: "member :test(> :is(byte, short, integer, long, bigInteger))")
structure lossless {}
