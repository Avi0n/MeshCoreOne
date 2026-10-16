package one.meshcore.shared.remotenode.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.room.ui.RoomStatusScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class RemoteSectionLazyTelemetryInstrumentedTest : RemoteSectionLazyTestSupport() {
    @Test
    fun repeaterStatusLoadsOnlyExpandedTelemetryAndKeepsPendingAndCachedReadings() {
        val model = fixture.workspaces.family(fixture.session).repeaterStatus()
        render {
            NodeStatusScreen(viewModel = model, session = fixture.session, surface = fixture.surface)
        }
        verifyTelemetry(
            name = "repeater-status",
            operation = "telemetry",
            loaded = { model.state.value.telemetryLoaded },
            refreshLabel = labels.reloadTelemetry,
        )
    }

    @Test
    fun roomStatusLoadsOnlyExpandedTelemetryAndKeepsPendingAndCachedReadings() {
        val model = fixture.workspaces.family(fixture.roomSession).roomStatus()
        render { RoomStatusScreen(viewModel = model, session = fixture.roomSession) }
        verifyTelemetry(
            name = "room-status",
            operation = "room-telemetry",
            loaded = { model.state.value.telemetryLoaded },
            refreshLabel = labels.refresh,
        )
    }

    @Test
    fun contactTelemetryLoadsOnlyOnExpansionAndKeepsPendingAndCachedReadings() {
        val model = NodeTelemetryViewModel(
            scope = scope,
            publicKey = fixture.session.publicKey,
            dependencies = NodeTelemetryViewModel.Dependencies(
                requestTelemetry = { fixture.radio::requestTelemetry },
                nodeSnapshotService = { fixture.snapshots },
                expectedPrefix = fixture.session.publicKey.copyOf(PREFIX_BYTES),
            ),
        )
        render { NodeTelemetryScreen(viewModel = model, contactName = fixture.session.name) }
        verifyTelemetry(
            name = "contact-telemetry",
            operation = "telemetry",
            loaded = { model.state.value.loaded },
            refreshLabel = labels.refresh,
        )
    }

    private fun verifyTelemetry(name: String, operation: String, loaded: () -> Boolean, refreshLabel: String) {
        assertCollapsed(labels.telemetry)
        compose.onNodeWithText(labels.noSensors).assertDoesNotExist()
        assertQuiet()
        capture("$name-collapsed")
        clickHeader(labels.telemetry)
        assertPendingOnly(operation)
        capture("$name-pending")
        retainPendingAcrossCollapse(labels.telemetry, operation)
        clickHeader(labels.telemetry)
        compose.runOnIdle {
            fixture.radio.completeTelemetry(
                if (operation == "room-telemetry") fixture.roomSession.publicKey else fixture.session.publicKey,
            )
        }
        compose.waitUntil(TIMEOUT_MS) { loaded() }
        assertCollapsed(labels.telemetry)
        compose.onNodeWithText(labels.noSensors).assertDoesNotExist()
        clickHeader(labels.telemetry)
        compose.onNodeWithText(labels.noSensors).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(operation), fixture.radio.calls) }
        capture("$name-cached")
        compose.onNodeWithContentDescription(refreshLabel).performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) { fixture.radio.calls.size == REFRESHED_CALL_COUNT }
        compose.waitUntil(TIMEOUT_MS) { loaded() }
        compose.runOnIdle { assertEquals(listOf(operation, operation), fixture.radio.calls) }
        compose.onNodeWithText(labels.noSensors).assertIsDisplayed()
        capture("$name-refreshed")
    }

    private companion object {
        const val PREFIX_BYTES = 6
        const val REFRESHED_CALL_COUNT = 2
    }
}
