package one.meshcore.shared.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import one.meshcore.shared.battery.OCVPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class BatteryCurveSettingsParentInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var fixture: BatteryCurveSettingsParentFixture

    @After
    fun tearDown() {
        if (::fixture.isInitialized) fixture.close()
    }

    @Test
    fun nativeKeyboardKeepsCompleteFirstAndLastFieldsVisibleAcrossFontChanges() {
        mount()
        val observations = mutableListOf<BatteryParentGeometry>()
        runCatching {
            listOf(null, LARGE_FONT_SCALE).forEachIndexed { index, fontScale ->
                compose.runOnIdle { fixture.fontScale = fontScale }
                fixture.hideKeyboard()
                fixture.exportState("geometry-$index-cold", FIRST_FIELD)
                fixture.fields().assertCountEquals(OCVPreset.POINT_COUNT)
                observations += holdField("geometry-$index-first", FIRST_FIELD)
                observations += holdField("geometry-$index-last", LAST_FIELD)
                fixture.hideKeyboard()
                fixture.openKeyboard()
                observations += fixture.settleGeometry(LAST_FIELD).also {
                    fixture.exportState("geometry-$index-last-reopened", LAST_FIELD, it)
                }
                fixture.hideKeyboard()
                fixture.exportState("geometry-$index-released", LAST_FIELD)
                compose.onNodeWithText(fixture.settingsTabLabel).assertIsSelected()
            }
            observations.forEach { observation ->
                assertTrue(
                    "Focused field has native IME: $observation",
                    observation.imeVisible && observation.imeBottom > 0,
                )
                assertTrue("Production field keeps focus: $observation", observation.focused)
                assertTrue(
                    "Keyboard does not pan the settings root above its native window: $observation",
                    observation.viewOrigin.y >= observation.window.top,
                )
                assertTrue(
                    "Full voltage field/value/unit above IME and inside parent: $observation",
                    observation.complete,
                )
                assertTrue("Every editable digit fits the native value area: $observation", observation.valueFits)
            }
        }.onFailure { failure ->
            runCatching { fixture.exportState("geometry-failure", LAST_FIELD) }.onFailure(failure::addSuppressed)
        }.getOrThrow()
    }

    @Test
    fun actualSectionCommitsOneValidCustomCurveAndReenablesAfterPendingSave() {
        mount()
        runCatching {
            focusedFiveDigitFontTransition().forEach { observation ->
                assertTrue("Five-digit draft stays focused above the native IME: $observation", observation.focused)
                assertTrue("Five-digit draft retains native IME: $observation", observation.imeVisible)
                assertTrue("Complete five-digit draft field/labels: $observation", observation.complete)
                assertTrue("All five native glyphs fit the editable value area: $observation", observation.valueFits)
            }
            compose.runOnIdle { fixture.holdSave = true }
            holdField("save-held", LAST_FIELD)
            fixture.field(LAST_FIELD).performTextReplacement(EDITED_MILLIVOLTS.toString())
            fixture.exportState("save-draft", LAST_FIELD)
            compose.runOnIdle {
                assertTrue("Focused draft does not invoke save", fixture.requests.isEmpty())
                assertEquals(OCVPreset.LI_ION.ocvArray, fixture.snapshot.values)
            }
            fixture.field(FIRST_FIELD).performScrollTo().performClick()
            compose.waitUntil(TIMEOUT_MS) { fixture.requests.size == 1 }
            fixture.exportState("save-pending", FIRST_FIELD)
            fixture.fields().assertCountEquals(OCVPreset.POINT_COUNT)
            fixture.fields().fetchSemanticsNodes().forEach { node ->
                assertTrue(
                    "Pending production save disables every voltage field",
                    node.config.contains(SemanticsProperties.Disabled),
                )
            }
            OCVPreset.selectablePresets.forEach { preset ->
                compose.onNodeWithText(preset.displayName).assertIsNotEnabled()
            }
            val expected = OCVPreset.LI_ION.ocvArray.toMutableList().also { it[LAST_FIELD] = EDITED_MILLIVOLTS }
            compose.runOnIdle {
                assertEquals(listOf(BatteryParentSave(OCVPreset.CUSTOM, expected)), fixture.requests.toList())
                fixture.saveRelease.complete(Unit)
            }
            compose.waitUntil(TIMEOUT_MS) { fixture.snapshot.values == expected }
            compose.waitForIdle()
            fixture.exportState("save-completed", FIRST_FIELD)
            compose.onNodeWithText(fixture.customLabel).assertExists()
            fixture.field(FIRST_FIELD).assertIsEnabled()
            OCVPreset.selectablePresets.forEach { preset ->
                compose.onNodeWithText(preset.displayName).assertIsEnabled()
            }
            compose.runOnIdle {
                assertEquals(OCVPreset.CUSTOM, fixture.snapshot.preset)
                assertEquals(1, fixture.requests.size)
            }
            fixture.hideKeyboard()
            fixture.field(LAST_FIELD).performScrollTo()
            fixture.exportState("save-final", LAST_FIELD)
            compose.runOnIdle { assertEquals("Keyboard dismissal does not repeat save", 1, fixture.requests.size) }
        }.onFailure { failure ->
            runCatching { fixture.exportState("save-failure", LAST_FIELD) }.onFailure(failure::addSuppressed)
        }.getOrThrow()
    }

    private fun mount() {
        fixture = BatteryCurveSettingsParentFixture(compose, compose.activity)
        fixture.mount()
    }

    private fun focusedFiveDigitFontTransition(): List<BatteryParentGeometry> {
        holdField("five-digit-held", FIRST_FIELD)
        fixture.field(FIRST_FIELD).performTextReplacement(FIVE_DIGIT_DRAFT)
        fixture.exportState("five-digit-normal", FIRST_FIELD)
        val normal = fixture.settleGeometry(FIRST_FIELD)
        compose.runOnIdle { fixture.fontScale = LARGE_FONT_SCALE }
        fixture.field(FIRST_FIELD).assertIsFocused().assertTextEquals(FIVE_DIGIT_DRAFT)
        fixture.exportState("five-digit-large-focused", FIRST_FIELD)
        val large = fixture.settleGeometry(FIRST_FIELD)
        compose.runOnIdle {
            assertTrue("Focused five-digit font transition does not save", fixture.requests.isEmpty())
            assertEquals(OCVPreset.LI_ION.ocvArray, fixture.snapshot.values)
            fixture.fontScale = null
        }
        fixture.field(FIRST_FIELD).assertIsFocused().assertTextEquals(FIVE_DIGIT_DRAFT)
        fixture.exportState("five-digit-restored-focused", FIRST_FIELD)
        val restored = fixture.settleGeometry(FIRST_FIELD)
        fixture.field(FIRST_FIELD).performTextReplacement(OCVPreset.LI_ION.ocvArray[FIRST_FIELD].toString())
        fixture.exportState("five-digit-reset-focused", FIRST_FIELD)
        compose.runOnIdle { assertTrue("Restoring the focused draft does not save", fixture.requests.isEmpty()) }
        return listOf(normal, large, restored)
    }

    private fun holdField(name: String, index: Int): BatteryParentGeometry {
        fixture.field(index).performScrollTo().performClick()
        fixture.openKeyboard()
        val geometry = fixture.settleGeometry(index)
        fixture.exportState(name, index, geometry)
        compose.onNodeWithText(fixture.settingsTabLabel).assertDoesNotExist()
        return geometry
    }

    private companion object {
        const val FIRST_FIELD = 0
        const val LAST_FIELD = OCVPreset.POINT_COUNT - 1
        const val LARGE_FONT_SCALE = 2f
        const val EDITED_MILLIVOLTS = 3050
        val FIVE_DIGIT_DRAFT = OCVPreset.VALID_MILLIVOLT_RANGE.last.toString()
        const val TIMEOUT_MS = 5_000L
    }
}
