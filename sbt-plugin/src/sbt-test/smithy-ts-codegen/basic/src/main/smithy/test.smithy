$version: "2"

namespace test

use alloy#simpleRestJson
use org.polyvariant.ndjson#ndjsonRestJson

structure Person {
    @required
    name: String
    age: Integer
}

@simpleRestJson
service Greeter {
    operations: [Greet]
}

@http(method: "POST", uri: "/greet/{name}")
operation Greet {
    input := {
        @required
        @httpLabel
        name: String
        greeting: String
    }
    output := {
        @required
        message: String
    }
}

/// Excluded from client generation; its data shapes must still be emitted.
@simpleRestJson
service HiddenService {
    operations: [Reveal]
}

@http(method: "GET", uri: "/secret")
operation Reveal {
    output := {
        @required
        secret: Secret
    }
}

structure Secret {
    @required
    value: String
}

/// Streams ndjson out and raw bytes in.
@ndjsonRestJson
service Feed {
    operations: [Watch, Upload]
}

@http(method: "GET", uri: "/watch")
operation Watch {
    output := {
        @required
        @httpPayload
        events: FeedEvent

        @httpHeader("x-session")
        session: String
    }
}

@http(method: "POST", uri: "/upload")
operation Upload {
    input := {
        @required
        @httpPayload
        body: Bytes
    }
}

@streaming
union FeedEvent {
    item: Person
    completed: Done
}

structure Done {}

@streaming
blob Bytes
