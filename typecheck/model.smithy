$version: "2"

// A model exercising every construct the codegen emits, so `tsc` sees the
// whole surface: data shapes, errors, a unary client, a streaming client
// (both framings, both directions), and the mock stubs for both.

namespace test

use alloy#discriminated
use alloy#jsonUnknown
use alloy#openEnum
use alloy#simpleRestJson
use org.polyvariant.ndjson#ndjsonRestJson
use org.polyvariant.smithy.ts#lossless

// --- Data shapes: one of every kind the generator handles ---

structure Person {
    /// The person's name, e.g. the value of `Person$name`. The `$` here is
    /// deliberate: doc text is model data, not a format string.
    @required
    name: String

    age: Integer

    @required
    kind: Kind

    tags: Tags

    scores: Scores

    id: PersonId

    registered: Timestamp

    active: Boolean

    payload: Document
}

enum Kind {
    ADMIN = "admin"
    USER = "user"
}

list Tags {
    member: String
}

map Scores {
    key: String
    value: Integer
}

/// A branded alias.
string PersonId

union Shape {
    person: Person
    label: String
}

// --- Errors: one status with a single error, one status shared by two ---

@error("client")
@httpError(404)
structure NotFound {
    @required
    message: String
}

@error("client")
@httpError(409)
structure Conflict {
    @required
    message: String
}

@error("client")
@httpError(409)
structure AlreadyExists {
    @required
    message: String
}

// --- A unary service: labels, query, headers, payload, response code ---

@simpleRestJson
service Directory {
    operations: [
        GetPerson
        PutPerson
        Search
        Ping
        Measure
        Sequence
    ]
    errors: [
        NotFound
    ]
}

@http(method: "GET", uri: "/people/{id}")
operation GetPerson {
    input := {
        @required
        @httpLabel
        id: String

        @httpQuery("verbose")
        verbose: Boolean
    }

    output := {
        @required
        person: Person

        @httpHeader("x-trace")
        trace: String

        @httpResponseCode
        code: Integer
    }

    errors: [
        Conflict
        AlreadyExists
    ]
}

@http(method: "PUT", uri: "/people/{id}")
operation PutPerson {
    input := {
        @required
        @httpLabel
        id: String

        @required
        @httpPayload
        person: Person

        @httpHeader("if-match")
        ifMatch: String
    }
}

@http(method: "GET", uri: "/search")
operation Search {
    input := {
        @httpQuery("q")
        q: String
    }

    output := {
        @required
        results: People
    }
}

list People {
    member: Person
}

/// No input, no output.
@http(method: "POST", uri: "/ping")
operation Ping {
}

// --- A streaming service: ndjson and binary, in and out ---

@ndjsonRestJson
service Feed {
    operations: [
        Watch
        Upload
        Echo
        Plain
    ]
    errors: [
        NotFound
    ]
}

/// Streams ndjson out, with a header alongside the stream.
@http(method: "GET", uri: "/watch/{id}")
operation Watch {
    input := {
        @required
        @httpLabel
        id: String

        @httpQuery("since")
        since: Timestamp
    }

    output := {
        @required
        @httpPayload
        events: FeedEvent

        @httpHeader("x-session")
        session: String
    }
}

/// Streams raw bytes in.
@http(method: "POST", uri: "/upload/{id}")
operation Upload {
    input := {
        @required
        @httpLabel
        id: String

        @required
        @httpPayload
        body: Bytes
    }

    output := {
        @required
        stored: Integer
    }
}

/// Streams ndjson in both directions.
@http(method: "POST", uri: "/echo")
operation Echo {
    input := {
        @required
        @httpPayload
        incoming: FeedEvent
    }

    output := {
        @required
        @httpPayload
        outgoing: FeedEvent
    }
}

/// A unary operation on a streaming service.
@http(method: "GET", uri: "/plain")
operation Plain {
    output := {
        @required
        message: String
    }
}

@streaming
union FeedEvent {
    item: Person
    completed: Done
    failed: Failure
}

structure Done {}

structure Failure {
    @required
    reason: String
}

@streaming
blob Bytes

// --- Open types: the server may send values this model does not list ---

/// An open enum: unrecognized values pass through as plain strings.
@openEnum
enum Category {
    BOOK = "book"
    FILM = "film"
}

/// An open union: an unrecognized discriminator key activates the
/// `@jsonUnknown` member, so the schema accepts any single-key object.
union Figure {
    circle: Circle

    square: Square

    @jsonUnknown
    other: Document
}

structure Circle {
    @required
    radius: Integer
}

structure Square {
    @required
    side: Integer
}

// --- @lossless: numeric members with no lossless JS `number` form ---

structure Measurement {
    /// Stays a `number` — an Integer always fits in a JS number.
    sequence: Integer

    /// Can exceed the JS safe-integer range, so it is typed `number | string`.
    seed: Long

    /// A `bigInteger` is unbounded, so it needs the trait even more than a `long`.
    precise: BigInteger

    @required
    label: String
}

apply Measurement$seed @lossless
apply Measurement$precise @lossless

/// A structure reusing the same `Long` shape *without* the trait, proving the
/// mapping is per-member and not per-shape.
structure Counter {
    @required
    total: Long
}

/// Exercises `@lossless` outside a JSON body: as a `@httpLabel`, a
/// `@httpQuery` and a `@httpHeader`, none of which may be coerced with
/// `Number(...)` once the member admits a string.
@http(method: "GET", uri: "/measurements/{seed}")
operation Measure {
    input := {
        @required
        @httpLabel
        seed: Long

        @httpQuery("offset")
        offset: Long

        @httpHeader("x-revision")
        revision: Long
    }

    output := {
        @required
        measurement: Measurement

        @httpHeader("x-total")
        total: Long
    }
}

apply MeasureInput$seed @lossless
apply MeasureInput$offset @lossless
apply MeasureInput$revision @lossless
apply MeasureOutput$total @lossless

/// Exercises `@lossless` *in* a JSON body, which is where a numeric string
/// would otherwise be written back quoted — changing the type the server sees.
/// The required member proves the coercion, the optional one proves an absent
/// member stays absent rather than becoming `BigInt(undefined)`.
@http(method: "POST", uri: "/sequences")
operation Sequence {
    input := {
        @required
        seed: Long

        cursor: Long

        @required
        count: Integer
    }

    output := {
        @required
        measurement: Measurement
    }
}

apply SequenceInput$seed @lossless
apply SequenceInput$cursor @lossless

// --- Discriminated unions: the variant is flattened and labelled ---

/// `{ "radius": 1, "kind": "circle" }` rather than `{ "circle": { ... } }`.
@discriminated("kind")
union Region {
    circle: Circle

    square: Square
}

/// Discriminated *and* open: the shape is known, only the label may not be.
@discriminated("kind")
union Zone {
    circle: Circle

    square: Square

    @jsonUnknown
    other: Document
}
