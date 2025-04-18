package org.greatfire.envoy

enum class EnvoyTestStatus {
    PASSED,   // one or more urls passed
    FAILED,   // all provided urls failed
    EMPTY,    // no urls were provided
    BLOCKED,  // all provided urls previously failed
    TIMEOUT,  // all tested urls failed and time ran out
    UNKNOWN   // unspecified failure state
}