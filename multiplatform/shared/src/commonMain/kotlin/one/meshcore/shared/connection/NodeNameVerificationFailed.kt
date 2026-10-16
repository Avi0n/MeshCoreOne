package one.meshcore.shared.connection

/** Thrown when the radio reads back a node name other than the one just written. */
class NodeNameVerificationFailed(val expected: String, val actual: String) :
    Exception("Node name verification failed: expected $expected, got $actual")
