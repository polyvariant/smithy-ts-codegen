$version: "2"

namespace test

use alloy#simpleRestJson

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
