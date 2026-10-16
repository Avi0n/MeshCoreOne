package one.meshcore.shared.room.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.chat.translation.MessageTranslationPhase
import one.meshcore.shared.chat.ui.stateOrFallback
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.testUuid
import one.meshcore.shared.room.roomMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomTranslateActionLabelTest {
    @Test
    fun translateLabelFollowsPhase() {
        val showing = MessageTranslationPhase.Showing(translatedText = "Hello everyone", targetLanguageCode = "en")
        assertEquals(RoomTranslateActionLabel.TRANSLATING, roomTranslateActionLabel(MessageTranslationPhase.InProgress))
        assertEquals(RoomTranslateActionLabel.SHOW_ORIGINAL, roomTranslateActionLabel(showing))
        assertEquals(RoomTranslateActionLabel.TRANSLATE, roomTranslateActionLabel(MessageTranslationPhase.Offer))
        assertEquals(RoomTranslateActionLabel.TRANSLATE, roomTranslateActionLabel(null))
    }

    @Test
    fun seedSkipsOffersThatAreOffAndSkipsSelfAndBlank() {
        val first = roomMessage(id = testUuid(2), text = "hello")
        val self = roomMessage(id = testUuid(3), text = "mine", isFromSelf = true)
        val blank = roomMessage(id = testUuid(4), text = "")
        val second = roomMessage(id = testUuid(5), text = "again")
        val seeded = mutableListOf<Pair<Uuid, String>>()
        runBlocking {
            seedRoomTranslations(offersEnabled = false, messages = listOf(first), seed = { id, text ->
                seeded += id to text
            })
        }
        assertTrue(seeded.isEmpty())
        runBlocking {
            seedRoomTranslations(
                offersEnabled = true,
                messages = listOf(first, self, blank, second),
                seed = { id, text -> seeded += id to text },
            )
        }
        assertEquals(listOf(first.id to "hello", second.id to "again"), seeded.toList())
    }

    @Test
    fun subtitleUsesPermissionOnlyWhileConnected() {
        assertEquals(
            "Member",
            roomDetailSubtitle(connected = true, permissionLabel = "Member", disconnectedLabel = "Off"),
        )
        assertEquals(
            "Off",
            roomDetailSubtitle(connected = false, permissionLabel = "Member", disconnectedLabel = "Off"),
        )
    }

    @Test
    fun linkTapWaitsUntilTheMessageSheetIsGone() {
        val opened = mutableListOf<LinkTarget>()
        val mention = LinkTarget.Mention("Alice")
        applyRoomLinkTap(messageSelected = true, target = mention, open = { opened += it })
        assertTrue(opened.isEmpty())
        applyRoomLinkTap(messageSelected = false, target = mention, open = { opened += it })
        assertEquals(listOf(mention), opened.toList())
    }

    @Test
    fun preferenceStateUsesTheFallbackOnlyWhenMissing() {
        val fallback = MutableStateFlow("en")
        val live = MutableStateFlow("es")
        assertEquals(fallback, stateOrFallback(state = null, fallback = fallback))
        assertEquals(live, stateOrFallback(state = live, fallback = fallback))
    }
}
