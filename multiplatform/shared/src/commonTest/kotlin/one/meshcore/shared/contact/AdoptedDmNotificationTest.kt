package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MessageEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdoptedDmNotificationTest {
    private val contactId = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
    private val radioId = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
    private val messageId = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")

    @Test
    fun blockedContactIsSilent() {
        assertNull(adoptedDmNotificationTarget(contact(isBlocked = true), unread()))
    }

    @Test
    fun missingUnreadIsSilent() {
        assertNull(adoptedDmNotificationTarget(contact(isBlocked = false), unread = null))
    }

    @Test
    fun unblockedWithUnreadPosts() {
        val target = adoptedDmNotificationTarget(contact(isBlocked = false, isMuted = true), unread())
        assertEquals(contactId, target?.first?.id)
        assertEquals("Bob", target?.first?.displayName)
        assertEquals(true, target?.first?.isMuted)
        assertEquals(messageId, target?.second?.id)
    }

    private fun contact(isBlocked: Boolean, isMuted: Boolean = false) = ContactDto(
        id = contactId,
        radioID = radioId,
        publicKey = ByteArray(32) { 0x11 },
        name = "Bob",
        typeRawValue = 1,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = isBlocked,
        isMuted = isMuted,
        isFavorite = false,
        unreadCount = 1,
    )

    private fun unread() = MessageEntity(
        id = messageId,
        radioID = radioId,
        text = "hello",
        timestamp = 1L,
        createdAt = 1.0,
        sortDate = 1.0,
        directionRawValue = 0,
        statusRawValue = 3,
        textTypeRawValue = 0,
        pathLength = 0,
        isRead = false,
        heardRepeats = 0,
        sendCount = 1,
        retryAttempt = 0,
        maxRetryAttempts = 0,
        linkPreviewFetched = false,
        containsSelfMention = false,
        mentionSeen = false,
        timestampCorrected = false,
        contactID = contactId,
    )
}
