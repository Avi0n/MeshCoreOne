package one.meshcore.shared.crypto

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.model.decodeHex
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.settings.KeyGenerationService
import one.meshcore.shared.settings.PlatformKeypairFromSeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Crypto vectors for Ed25519 identity material. RFC 8032 secret-key test vector 1.
 */
class Ed25519KeysTest {
    @Test
    fun `RFC 8032 vector 1 public key from seed`() {
        // SECRET KEY (seed):
        // 9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60
        // PUBLIC KEY:
        // d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a
        val seed = hexToBytes(
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
        )
        val expectedPublic = hexToBytes(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
        )
        val public = Ed25519Keys.publicKeyFromSeed(seed)
        assertEquals(expectedPublic.toList(), public.toList())
    }

    @Test
    fun `expanded seed has RFC 8032 clamping`() {
        val seed = ByteArray(32) { (it + 1).toByte() }
        val expanded = Ed25519Keys.expandSeed(seed)
        assertEquals(64, expanded.size)
        assertEquals(0, expanded[0].toInt() and 0x07)
        assertEquals(0, expanded[31].toInt() and 0x80)
        assertEquals(0x40, expanded[31].toInt() and 0x40)
    }

    @Test
    fun `platform keypair matches expand and public helpers`() {
        val seed = ByteArray(32) { 0x5A }
        val derived = PlatformKeypairFromSeed.derive(seed)
        assertEquals(Ed25519Keys.expandSeed(seed).toList(), derived.expandedPrivateKey.toList())
        assertEquals(Ed25519Keys.publicKeyFromSeed(seed).toList(), derived.publicKey.toList())
    }

    @Test
    fun `generateIdentity without prefix never returns reserved first byte`() = runBlocking {
        repeat(8) {
            val id = KeyGenerationService.generateIdentity(hexPrefix = null)
            val first = id.publicKey[0].toInt() and 0xFF
            assertTrue(first != 0x00 && first != 0xFF, "reserved first byte $first")
            assertEquals(64, id.expandedPrivateKey.size)
            assertEquals(32, id.publicKey.size)
        }
    }

    @Test
    fun `1-char vanity prefix is respected`() = runBlocking {
        val id = KeyGenerationService.generateIdentity(hexPrefix = "A")
        val hex = id.publicKey.toUppercaseHex()
        assertTrue(hex.startsWith("A"), "got $hex")
    }

    private fun hexToBytes(hex: String): ByteArray = requireNotNull(decodeHex(hex))
}
