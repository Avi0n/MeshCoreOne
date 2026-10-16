package one.meshcore.shared.settings

import one.meshcore.shared.crypto.Ed25519Keys

actual object PlatformKeypairFromSeed : KeyGenerationService.KeypairFromSeed {
    actual override fun derive(seed: ByteArray): KeyGenerationService.GeneratedIdentity =
        KeyGenerationService.GeneratedIdentity(
            expandedPrivateKey = Ed25519Keys.expandSeed(seed),
            publicKey = Ed25519Keys.publicKeyFromSeed(seed),
        )
}
