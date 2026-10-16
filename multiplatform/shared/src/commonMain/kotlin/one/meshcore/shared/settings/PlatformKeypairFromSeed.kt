package one.meshcore.shared.settings

/**
 * Platform Ed25519 seed → public + expanded private key. Actual on jvmCommon (Android + desktop).
 */
expect object PlatformKeypairFromSeed : KeyGenerationService.KeypairFromSeed {
    override fun derive(seed: ByteArray): KeyGenerationService.GeneratedIdentity
}
