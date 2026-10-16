package one.meshcore.shared.remotenode.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.remotenode.LPPDataPoint
import one.meshcore.shared.remotenode.LPPSensorType
import one.meshcore.shared.remotenode.LPPValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class RemoteSectionLazyHistoryInstrumentedTest : RemoteSectionLazyTestSupport() {
    @Test
    fun firstTelemetryReadingReplacesInitiallyEmptyHistoryWithItsPersistedSample() {
        val model = fixture.workspaces.family(fixture.session).repeaterStatus()
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
        assertQuiet()
        compose.onNodeWithText(labels.telemetryTab).performScrollTo().performClick()
        compose.waitForIdle()
        assertCollapsed(labels.telemetry)
        assertQuiet()
        clickHeader(labels.telemetry)
        assertPendingOnly("telemetry")
        compose.waitUntil(TIMEOUT_MS) { fixture.snapshotReads == 1 }
        compose.runOnIdle {
            assertTrue(fixture.lastHistoryRows.isEmpty())
            assertEquals(0, fixture.persistenceWrites)
        }
        capture("management-telemetry-empty-history")
        compose.runOnIdle {
            fixture.radio.completeTelemetry(
                fixture.session.publicKey,
                listOf(LPPDataPoint(SENSOR_CHANNEL, LPPSensorType.VOLTAGE, LPPValue.FloatValue(VOLTAGE))),
            )
        }
        compose.waitUntil(TIMEOUT_MS) {
            fixture.lastHistoryRows.singleOrNull()?.telemetryEntries?.singleOrNull()?.value == VOLTAGE
        }
        compose.runOnIdle {
            assertEquals(listOf("telemetry"), fixture.radio.calls)
            assertEquals(1, fixture.persistenceWrites)
            assertEquals(1L, model.state.value.snapshotVersion)
            assertTrue(fixture.snapshotReads >= HISTORY_READS_AFTER_CAPTURE)
        }
        compose.onNodeWithText(labels.history).performScrollTo().performClick()
        compose.onNode(hasText(labels.voltage) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        val values = compose.onAllNodes(hasText(VOLTAGE_CAPTION) and hasAnyAncestor(isDialog()))
        values.assertCountEquals(2)
        values[0].assertIsDisplayed()
        values[1].assertIsDisplayed()
        capture("management-telemetry-new-history", dialog = true)
    }

    private companion object {
        const val SENSOR_CHANNEL = 1
        const val VOLTAGE = 3.99
        const val VOLTAGE_CAPTION = "3.99 V"
        const val HISTORY_READS_AFTER_CAPTURE = 2
    }
}
