package one.meshcore.shared.crypto

import java.security.SecureRandom

// One actual for both JVM targets (Android + desktop), mirroring Sha256.jvm.kt: java.security.SecureRandom
// is present on both, so a duplicated per-target actual would add nothing. A single shared instance is
// thread-safe and self-seeding.
private val secureRandom = SecureRandom()

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also(secureRandom::nextBytes)
