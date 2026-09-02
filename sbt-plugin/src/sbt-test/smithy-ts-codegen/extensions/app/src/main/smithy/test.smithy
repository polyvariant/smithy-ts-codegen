$version: "2"

namespace test

use alloy#simpleRestJson

@simpleRestJson
service Greeter {
    version: "v1"
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
