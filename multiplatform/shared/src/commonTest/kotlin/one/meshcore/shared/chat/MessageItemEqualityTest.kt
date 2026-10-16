package one.meshcore.shared.chat

import one.meshcore.shared.chat.text.FormattedText
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Value-equality completeness for the item-build value types. Equality drives list diffing, so a field
 * missing from equality would silently render a stale row. Kotlin `data class` derives equality over every
 * declared property, so this suite pins that guarantee: two instances differing in any single field are
 * unequal, and identical instances are equal (and hash equal). If a field is ever dropped from a type or
 * equality is hand-written and misses one, one of these assertions fails.
 */
class MessageItemEqualityTest {
    private val id = Uuid.normalized("11111111-1111-1111-1111-111111111111")
    private val otherId = Uuid.normalized("22222222-2222-2222-2222-222222222222")

    private val resolution = NodeNameResolution(displayName = "N", matchKind = NodeNameMatchKind.EXACT)

    private val payload = MessageTextPayload(
        raw = "hi",
        formattedText = null,
        baseColor = BaseColorSlot.INCOMING,
        isOutgoing = false,
        currentUserName = "Me",
    )

    private val envelope = MessageEnvelope(
        messageID = id,
        isOutgoing = false,
        senderName = "N",
        senderResolution = resolution,
        status = MessageStatus.SENT,
        dateEpochSeconds = 100.0,
        containsSelfMention = false,
        mentionSeen = false,
    )

    private val footer = MessageFooter(
        showStatusRow = true,
        status = MessageStatus.SENT,
        isChannelMessage = false,
        heardRepeats = 0,
        retryAttempt = 0,
        maxRetryAttempts = 0,
        sendCount = 1,
        sendTimeToShowEpochSeconds = null,
        sendTimeWasCorrected = false,
        showHop = false,
        hopCount = 0,
        formattedPath = null,
        regionToShow = null,
    )

    private val grouping = GroupingFlags(
        showTimestamp = false,
        showDirectionGap = false,
        showSenderName = false,
        showNewMessagesDivider = false,
        showDayDivider = false,
    )

    private val availability = MessageActionAvailability(
        canReply = true,
        canCopy = true,
        canSendAgain = false,
        canBlockSender = false,
        canSendDM = false,
        canShowRepeatDetails = false,
        canViewPath = false,
        canDelete = true,
    )

    private val item = MessageItem(
        id = id,
        envelope = envelope,
        content = listOf(MessageFragment.Text(payload)),
        footer = footer,
        grouping = grouping,
        shouldRequestPreviewFetch = false,
        actionAvailability = availability,
    )

    private fun <T> assertFieldsDistinguish(base: T, variants: List<T>) {
        for (variant in variants) {
            assertNotEquals(base, variant)
            assertNotEquals(base.hashCode(), variant.hashCode())
        }
    }

    @Test
    fun `MessageTextPayload distinguishes every field`() {
        assertEquals(payload, payload.copy())
        assertFieldsDistinguish(
            payload,
            listOf(
                payload.copy(raw = "bye"),
                payload.copy(formattedText = FormattedText("hi", emptyList())),
                payload.copy(baseColor = BaseColorSlot.OUTGOING),
                payload.copy(isOutgoing = true),
                payload.copy(currentUserName = "You"),
            ),
        )
    }

    @Test
    fun `MessageEnvelope distinguishes every field`() {
        assertEquals(envelope, envelope.copy())
        assertFieldsDistinguish(
            envelope,
            listOf(
                envelope.copy(messageID = otherId),
                envelope.copy(isOutgoing = true),
                envelope.copy(senderName = "M"),
                envelope.copy(senderResolution = resolution.copy(matchKind = NodeNameMatchKind.FALLBACK)),
                envelope.copy(status = MessageStatus.DELIVERED),
                envelope.copy(dateEpochSeconds = 200.0),
                envelope.copy(containsSelfMention = true),
                envelope.copy(mentionSeen = true),
            ),
        )
    }

    @Test
    fun `MessageFooter distinguishes every field`() {
        assertEquals(footer, footer.copy())
        assertFieldsDistinguish(
            footer,
            listOf(
                footer.copy(showStatusRow = false),
                footer.copy(status = MessageStatus.FAILED),
                footer.copy(isChannelMessage = true),
                footer.copy(heardRepeats = 1),
                footer.copy(retryAttempt = 1),
                footer.copy(maxRetryAttempts = 5),
                footer.copy(sendCount = 2),
                footer.copy(sendTimeToShowEpochSeconds = 100.0),
                footer.copy(sendTimeWasCorrected = true),
            ),
        )
    }

    @Test
    fun `GroupingFlags distinguishes every field`() {
        assertEquals(grouping, grouping.copy())
        assertFieldsDistinguish(
            grouping,
            listOf(
                grouping.copy(showTimestamp = true),
                grouping.copy(showDirectionGap = true),
                grouping.copy(showSenderName = true),
                grouping.copy(showNewMessagesDivider = true),
                grouping.copy(showDayDivider = true),
            ),
        )
    }

    @Test
    fun `MessageItem distinguishes every field`() {
        assertEquals(item, item.copy())
        assertEquals(item.hashCode(), item.copy().hashCode())
        assertFieldsDistinguish(
            item,
            listOf(
                item.copy(id = otherId),
                item.copy(envelope = envelope.copy(status = MessageStatus.DELIVERED)),
                item.copy(content = listOf(MessageFragment.Text(payload.copy(raw = "changed")))),
                item.copy(footer = footer.copy(heardRepeats = 9)),
                item.copy(grouping = grouping.copy(showTimestamp = true)),
                item.copy(shouldRequestPreviewFetch = true),
                item.copy(actionAvailability = availability.copy(canViewPath = true)),
            ),
        )
    }
}
