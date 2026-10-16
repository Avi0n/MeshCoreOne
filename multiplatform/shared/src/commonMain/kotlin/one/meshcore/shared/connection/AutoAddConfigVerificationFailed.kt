package one.meshcore.shared.connection

/** Thrown when the radio reads back an auto-add policy other than the one just written. */
class AutoAddConfigVerificationFailed(val expected: String, val actual: String) :
    Exception("Auto-add config verification failed: expected $expected, got $actual")
