import { isSafeNumber, parse, stringify } from 'lossless-json'

/**
 * JSON serialisation that preserves numeric values `JSON.parse` /
 * `JSON.stringify` would destroy.
 *
 * JavaScript numbers are IEEE-754 doubles, so an integer outside ±(2^53 - 1)
 * cannot be held exactly. That matters for a Smithy `long`, `bigInteger` or
 * `bigDecimal`, whose range is wider: `JSON.parse` rounds such a value on the
 * way in, before any schema can see it, and the original is unrecoverable.
 * Members declared `@lossless` are typed `number | string` for exactly this
 * reason, and these two functions are what make that type honest.
 */

/**
 * Parse a JSON document, keeping every numeric value exact.
 *
 * A number that round-trips exactly comes back as a `number`, so ordinary
 * fields are untouched and existing schemas keep seeing what they expect. Only
 * a value that would lose precision surfaces as its exact decimal string —
 * which is why `@lossless` members admit both.
 */
export const parseLossless = (text: string): unknown =>
  parse(text, undefined, (value) => (isSafeNumber(value) ? Number(value) : value))

/**
 * Serialise a request body, emitting `@lossless` members as bare numeric
 * literals.
 *
 * The generated client hands a `@lossless` member over as a `bigint`, which is
 * written unquoted and at full range — `JSON.stringify` throws on a `bigint`,
 * and quoting the decimal string instead would change the type the server sees.
 *
 * Like `JSON.stringify`, the underlying serializer returns `undefined` for a
 * value that has no JSON representation at all (`undefined` itself, a function,
 * a symbol). A request body is checked for `undefined` before it gets here, so
 * that cannot be the whole body; anything else in that class is a caller bug,
 * and `'null'` keeps the request well-formed rather than sending the literal
 * text `undefined`.
 */
export const stringifyLossless = (value: unknown): string => stringify(value) ?? 'null'
