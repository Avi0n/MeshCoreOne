package one.meshcore.shared.tools.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_noise_floor_d_bm
import one.meshcore.shared.resources.tools_noise_floor_quality_excellent
import one.meshcore.shared.resources.tools_noise_floor_quality_fair
import one.meshcore.shared.tools.NoiseFloorReading
import one.meshcore.shared.tools.currentReadingAccessibilityLabel
import org.jetbrains.compose.resources.stringResource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoiseFloorOwnerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun replacementSessionClearsRetiredRadioSamples() {
        var sessionOwner by mutableStateOf<Flow<MeshEvent>?>(MutableSharedFlow())
        var radioId by mutableStateOf(FIRST_RADIO)
        var noiseFloor = FIRST_NOISE_FLOOR
        lateinit var firstReadingLabel: String
        lateinit var replacementReadingLabel: String

        compose.setContent {
            val dbm = stringResource(Res.string.tools_noise_floor_d_bm)
            firstReadingLabel = currentReadingAccessibilityLabel(
                FIRST_NOISE_FLOOR,
                dbm,
                stringResource(Res.string.tools_noise_floor_quality_excellent),
                "",
            )
            replacementReadingLabel = currentReadingAccessibilityLabel(
                REPLACEMENT_NOISE_FLOOR,
                dbm,
                stringResource(Res.string.tools_noise_floor_quality_fair),
                "",
            )
            MaterialTheme {
                NoiseFloorScreen(
                    fetchReading = { reading(noiseFloor) },
                    sessionOwner = sessionOwner,
                    radioId = radioId,
                )
            }
        }
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithContentDescription(firstReadingLabel).fetchSemanticsNodes().isNotEmpty()
        }

        compose.runOnIdle {
            noiseFloor = REPLACEMENT_NOISE_FLOOR
            sessionOwner = MutableSharedFlow()
            radioId = REPLACEMENT_RADIO
        }
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithContentDescription(replacementReadingLabel).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("$FIRST_NOISE_FLOOR dBm").assertDoesNotExist()
    }

    private fun reading(noiseFloor: Int) = NoiseFloorReading(
        id = Uuid.normalized("B0000000-0000-0000-0000-${noiseFloor.toString().removePrefix("-").padStart(12, '0')}"),
        timestampEpochMs = noiseFloor.toLong(),
        noiseFloor = noiseFloor,
        lastRssi = -70,
        lastSnr = 5.0,
    )

    private companion object {
        const val FIRST_NOISE_FLOOR = -100
        const val REPLACEMENT_NOISE_FLOOR = -80
        const val TIMEOUT_MS = 5_000L
        val FIRST_RADIO: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val REPLACEMENT_RADIO: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000002")
    }
}
