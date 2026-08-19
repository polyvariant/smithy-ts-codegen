$version: "2"

// A model exercising every construct the codegen emits, so `tsc` sees the
// whole surface: data shapes, errors, a unary client, a streaming client
// (both framings, both directions), and the mock stubs for both.

namespace test

use alloy#simpleRestJson
use org.polyvariant.ndjson#ndjsonRestJson

// --- Data shapes: one of every kind the generator handles ---

structure Person {
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
