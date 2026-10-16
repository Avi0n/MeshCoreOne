package one.meshcore.shared.crypto

import java.security.MessageDigest

// One actual for both JVM targets (Android + desktop), mirroring the source-set choice the TCP socket
// adapter makes: java.security.MessageDigest is present on both, so an expect/actual pair with a
// duplicated per-target actual would add nothing.
actual fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)
