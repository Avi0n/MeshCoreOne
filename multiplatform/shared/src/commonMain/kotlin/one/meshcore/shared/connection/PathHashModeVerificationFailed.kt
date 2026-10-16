package one.meshcore.shared.connection

/** Thrown when the radio reads back a path-hash mode other than the one just written. */
class PathHashModeVerificationFailed(val expected: Int, val actual: Int) :
    Exception("Path hash mode verification failed: expected $expected, got $actual")
