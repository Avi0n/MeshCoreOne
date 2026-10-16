package one.meshcore.shared.crypto

/**
 * SHA-256 of [input]. The only cryptographic primitive the connection layer needs: WiFi-bridged radios
 * have no Bluetooth handle, so their `Device` row id is derived from the public key's SHA-256 (see
 * [one.meshcore.shared.identity.DeviceIdentity]). The digest must be byte-identical to Swift's
 * `CryptoKit.SHA256`, so the actual delegates to the platform's standard implementation rather than a
 * hand-rolled one.
 */
expect fun sha256(input: ByteArray): ByteArray
