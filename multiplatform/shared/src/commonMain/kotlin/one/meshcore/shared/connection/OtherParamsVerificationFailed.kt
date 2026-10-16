package one.meshcore.shared.connection

/** Thrown when the radio reads back other-params values other than the ones just written. */
class OtherParamsVerificationFailed(val expected: String, val actual: String) :
    Exception("Device parameter verification failed: expected $expected, got $actual")
