package one.meshcore.shared.map.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.map.MapDuration
import one.meshcore.shared.map.MapDurationUnit
import one.meshcore.shared.map.MapFilterState
import one.meshcore.shared.map.MapHopRange
import one.meshcore.shared.map.MapLastHeardRange
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.map_filters_hops_direct
import one.meshcore.shared.resources.map_filters_minimum
import one.meshcore.shared.resources.map_filters_remove
import one.meshcore.shared.resources.map_filters_reset
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapAdvancedFiltersInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun directPresetAndResetChangeOnlyLocalAdvancedFilters() {
        val filter = mutableStateOf(MapFilterState(favoritesOnly = true))
        lateinit var directLabel: String
        lateinit var resetLabel: String
        compose.setContent {
            directLabel = stringResource(Res.string.map_filters_hops_direct)
            resetLabel = stringResource(Res.string.map_filters_reset)
            MaterialTheme {
                MapAdvancedFiltersSheet(filter.value, { filter.value = it }, {})
            }
        }
        compose.onNodeWithText(directLabel).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(MapHopRange.DIRECT, filter.value.hops)
            assertTrue(filter.value.favoritesOnly)
            assertEquals(filter.value, MapFilterState.fromStorageString(filter.value.toStorageString()))
        }
        compose.onNodeWithText(resetLabel).performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(filter.value.hops.isActive)
            assertFalse(filter.value.lastHeard.isActive)
            assertTrue(filter.value.favoritesOnly)
        }
    }

    @Test
    fun clearingAndRetypingDurationKeepsTheChosenUnit() {
        val duration = mutableStateOf<MapDuration?>(MapDuration(2, MapDurationUnit.DAYS))
        compose.setContent {
            MaterialTheme {
                MapDurationBoundField(stringResource(Res.string.map_filters_minimum), duration.value) {
                    duration.value = it
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.runOnIdle { assertNull(duration.value) }
        compose.onNode(hasSetTextAction()).performClick().performTextInput("3")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.runOnIdle { assertEquals(MapDuration(3, MapDurationUnit.DAYS), duration.value) }
    }

    @Test
    fun removingOneChipKeepsTheOtherAndItsEditAction() {
        val filter = mutableStateOf(
            MapFilterState(
                lastHeard = MapLastHeardRange.within(MapDuration(1, MapDurationUnit.HOURS)),
                hops = MapHopRange.DIRECT,
            ),
        )
        lateinit var removeHops: String
        lateinit var heardLabel: String
        var editCount = 0
        compose.setContent {
            removeHops = formattedStringResource(Res.string.map_filters_remove, MapHopRange.DIRECT.label())
            heardLabel = filter.value.lastHeard.label()
            MaterialTheme {
                MapActiveFiltersBar(filter.value, { filter.value = it }, { editCount++ })
            }
        }
        compose.onNodeWithContentDescription(removeHops).performClick()
        compose.runOnIdle {
            assertFalse(filter.value.hops.isActive)
            assertTrue(filter.value.lastHeard.isActive)
            assertEquals(0, editCount)
        }
        compose.onNodeWithText(heardLabel).performClick()
        compose.runOnIdle { assertEquals(1, editCount) }
    }
}
