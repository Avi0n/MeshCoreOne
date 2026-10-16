package one.meshcore.shared.crypto

import java.security.MessageDigest

actual fun sha512(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(input)
