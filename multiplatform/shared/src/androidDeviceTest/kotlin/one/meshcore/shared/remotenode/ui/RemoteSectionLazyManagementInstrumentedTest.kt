package one.meshcore.shared.remotenode.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.meshcore.OwnerInfoResponse
import one.meshcore.shared.room.ui.RoomManagementSurface
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class RemoteSectionLazyManagementInstrumentedTest : RemoteSectionLazyTestSupport() {
    @Test
    fun repeaterManagementLoadsOnlyExpandedContactInfoAndRetainsItsPendingRead() {
        val model = fixture.workspaces.family(fixture.session).repeaterSettings()
        render {
            NodeManagementSurface(
                session = fixture.session,
                routePathContact = fixture.contact,
                repeaterAdminService = { fixture.admin },
                nodeSnapshotService = { fixture.snapshots },
                surface = fixture.surface,
                workspaces = fixture.workspaces,
            )
        }
        assertManagementInitiallyQuiet("repeater")
        assertLocalSectionsStayQuiet()
        clickHeader(labels.contactInfo)
        assertPendingOnly("owner")
        capture("repeater-contact-pending")
        retainPendingAcrossCollapse(labels.contactInfo, "owner")
        clickHeader(labels.contactInfo)
        compose.runOnIdle {
            fixture.radio.ownerReply.complete(OwnerInfoResponse("1.15.0", fixture.session.name, OWNER_INFO))
        }
        compose.waitUntil(TIMEOUT_MS) { model.state.value.contactInfoLoaded }
        assertCollapsed(labels.contactInfo)
        compose.onNodeWithText(OWNER_INFO).assertDoesNotExist()
        clickHeader(labels.contactInfo)
        compose.onNodeWithText(OWNER_INFO).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("owner"), fixture.radio.calls) }
        capture("repeater-contact-cached")
    }

    @Test
    fun roomManagementLoadsOnlyExpandedContactInfoAndRetainsItsPendingRead() {
        val model = fixture.workspaces.family(fixture.roomSession).roomSettings()
        render {
            RoomManagementSurface(
                session = fixture.roomSession,
                roomAdminService = { fixture.roomAdmin },
                nodeSnapshotService = { fixture.snapshots },
                contactService = { fixture.contacts },
                radioId = { fixture.session.radioID },
                workspaces = fixture.workspaces,
            )
        }
        assertManagementInitiallyQuiet("room")
        assertLocalSectionsStayQuiet()
        clickHeader(labels.contactInfo)
        assertPendingOnly(ROOM_OWNER_REQUEST)
        capture("room-contact-pending")
        retainPendingAcrossCollapse(labels.contactInfo, ROOM_OWNER_REQUEST)
        clickHeader(labels.contactInfo)
        compose.runOnIdle { fixture.radio.roomOwnerReply.complete(OWNER_INFO) }
        compose.waitUntil(TIMEOUT_MS) { model.state.value.contactInfoLoaded }
        assertCollapsed(labels.contactInfo)
        compose.onNodeWithText(OWNER_INFO).assertDoesNotExist()
        clickHeader(labels.contactInfo)
        compose.onNodeWithText(OWNER_INFO).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(ROOM_OWNER_REQUEST), fixture.radio.calls) }
        capture("room-contact-cached")
    }

    private fun assertManagementInitiallyQuiet(name: String) {
        assertCollapsed(labels.contactInfo)
        assertCollapsed(labels.security)
        assertCollapsed(labels.actions)
        compose.onNodeWithText(labels.contactEditor).assertDoesNotExist()
        compose.onNodeWithText(labels.password).assertDoesNotExist()
        compose.onNodeWithText(labels.sendAdvert).assertDoesNotExist()
        assertQuiet()
        capture("$name-management-collapsed")
    }

    private fun assertLocalSectionsStayQuiet() {
        clickHeader(labels.security)
        compose.onNodeWithText(labels.password).performScrollTo().assertIsDisplayed()
        assertQuiet()
        clickHeader(labels.security)
        clickHeader(labels.actions)
        compose.onNodeWithText(labels.sendAdvert).performScrollTo().assertIsDisplayed()
        assertQuiet()
        clickHeader(labels.actions)
    }

    private companion object {
        const val OWNER_INFO = "Selected section owner"
        const val ROOM_OWNER_REQUEST = "room-cli:get owner.info"
    }
}
