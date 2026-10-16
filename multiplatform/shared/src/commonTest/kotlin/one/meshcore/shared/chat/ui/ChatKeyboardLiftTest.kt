package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Mirrors iOS `ChatKeyboardLiftTests` interactive-pop cases. */
class ChatKeyboardLiftTest {
    @Test
    fun navigationInsetIsOnlyReservedWhileTheKeyboardIsClosed() {
        assertEquals(120, navigationInsetOutsideIme(imeBottom = 0, navigationBottom = 120))
        assertEquals(0, navigationInsetOutsideIme(imeBottom = 900, navigationBottom = 120))
    }

    @Test
    fun imeLiftSubtractsNavigationAreaAlreadyExcludedFromContent() {
        assertEquals(755, imeLiftInsideNavigationBounds(imeBottom = 887, navigationBottom = 132))
        assertEquals(0, imeLiftInsideNavigationBounds(imeBottom = 0, navigationBottom = 132))
        assertEquals(0, imeLiftInsideNavigationBounds(imeBottom = 100, navigationBottom = 132))
    }

    @Test
    fun interactivePopKeepsCurrentLiftOnADrop() {
        val resolved = ChatKeyboardLift.resolvedLift(
            current = 302f,
            proposed = 0f,
            isInteractivePopActive = true,
        )
        assertEquals(302f, resolved)
    }

    @Test
    fun hideWithoutInteractivePopAppliesProposedLift() {
        val resolved = ChatKeyboardLift.resolvedLift(
            current = 302f,
            proposed = 0f,
            isInteractivePopActive = false,
        )
        assertEquals(0f, resolved)
    }

    @Test
    fun placedLiftNotifiesNativeLayoutOnlyOnChangeIncludingReentrantPlacement() {
        var requests = 0
        var placedLift = 0
        lateinit var notifications: ChatKeyboardLift.LayoutNotifications
        notifications = ChatKeyboardLift.LayoutNotifications(placedLift) {
            requests += 1
            notifications.onPlaced(placedLift)
        }
        notifications.onPlaced(placedLift)
        assertEquals(0, requests)
        placedLift = 302
        notifications.onPlaced(placedLift)
        notifications.onPlaced(placedLift)
        assertEquals(1, requests)
        placedLift = ChatKeyboardLift.resolvedLift(302f, 0f, isInteractivePopActive = true).toInt()
        notifications.onPlaced(placedLift)
        assertEquals(1, requests)
        placedLift = ChatKeyboardLift.resolvedLift(302f, 0f, isInteractivePopActive = false).toInt()
        notifications.onPlaced(placedLift)
        assertEquals(2, requests)
    }
}
