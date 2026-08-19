$version: "2"

namespace org.polyvariant.smithy.ts

/// Represent this numeric member as a `string` in the generated TypeScript.
///
/// JavaScript numbers are IEEE-754 doubles, so integers outside
/// ±(2^53 - 1) cannot be represented exactly: reading one into a `number`
/// silently rounds it, and there is no way to recover the original value.
/// A `long`, `bigInteger` or `bigDecimal` whose values can exceed that range
/// therefore has no lossless `number` representation on the client.
///
/// Applying this trait maps the member to `z.string()` / `string` instead,
/// leaving the value exactly as it appeared on the wire. Note that this only
/// changes the *TypeScript* representation — the wire format is still a JSON
/// number, so a client sending such a member is responsible for serializing
/// it back as an unquoted numeric literal.
///
/// The trait is deliberately member-scoped rather than shape-scoped: whether a
/// given field can exceed the safe range is a property of that field, and the
/// same numeric shape is often reused for values that stay well inside it.
@trait(
    selector: "member :test(> :is(byte, short, integer, long, float, double, bigInteger, bigDecimal))"
)
structure mapToString {}
