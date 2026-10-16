package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * Process-lifetime JPEG bytes for cluster-end photos. Mirrors iOS `IncomingAvatarJPEGStoreTests`.
 */
class IncomingAvatarJPEGStoreTest {
    @Test
    fun replaceStoresJpegOnlyWhenMatchedContactIdOwnsTheBytes() {
        IncomingAvatarJPEGStore.withIsolatedStorage {
            val contact = ChatFixtures.contact(name = "Maya").copy(avatarImageData = JPEG)
            IncomingAvatarJPEGStore.replace(
                contacts = listOf(contact),
                identities = IncomingAvatarIdentity.identitiesFrom(listOf(contact)).values,
            )
            assertContentEquals(JPEG, IncomingAvatarJPEGStore.data(contact.id))
            assertNull(IncomingAvatarJPEGStore.data(ChatFixtures.freshUuid()))
        }
    }

    @Test
    fun nestedIsolatedStorageDoesNotClobberTheOuterSession() {
        IncomingAvatarJPEGStore.withIsolatedStorage {
            val outer = ChatFixtures.contact(name = "Maya").copy(avatarImageData = OUTER)
            IncomingAvatarJPEGStore.replace(
                contacts = listOf(outer),
                identities = IncomingAvatarIdentity.identitiesFrom(listOf(outer)).values,
            )
            assertContentEquals(OUTER, IncomingAvatarJPEGStore.data(outer.id))

            IncomingAvatarJPEGStore.withIsolatedStorage {
                val inner = ChatFixtures.contact(name = "Rico").copy(avatarImageData = INNER)
                IncomingAvatarJPEGStore.replace(
                    contacts = listOf(inner),
                    identities = IncomingAvatarIdentity.identitiesFrom(listOf(inner)).values,
                )
                assertContentEquals(INNER, IncomingAvatarJPEGStore.data(inner.id))
                assertNull(IncomingAvatarJPEGStore.data(outer.id))
            }

            assertContentEquals(OUTER, IncomingAvatarJPEGStore.data(outer.id))
        }
    }

    @Test
    fun emptyJpegIsNotStored() {
        IncomingAvatarJPEGStore.withIsolatedStorage {
            val contact = ChatFixtures.contact(name = "Maya").copy(avatarImageData = ByteArray(0))
            IncomingAvatarJPEGStore.replace(
                contacts = listOf(contact),
                identities = IncomingAvatarIdentity.identitiesFrom(listOf(contact)).values,
            )
            assertNull(IncomingAvatarJPEGStore.data(contact.id))
        }
    }

    private companion object {
        val JPEG = byteArrayOf(1, 2, 3, 4)
        val OUTER = byteArrayOf(9, 9, 9)
        val INNER = byteArrayOf(8, 8, 8)
    }
}
